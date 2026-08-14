package com.qar.securitysystem;

import com.qar.securitysystem.abe.AccessPurpose;
import com.qar.securitysystem.abe.lattice.LatticeAbeService;
import com.qar.securitysystem.dto.EncryptedFileUploadRequest;
import com.qar.securitysystem.dto.FileRecordResponse;
import com.qar.securitysystem.dto.FlightXlsxPreviewResponse;
import com.qar.securitysystem.model.FileRecordEntity;
import com.qar.securitysystem.model.PersonRecordEntity;
import com.qar.securitysystem.model.UserEntity;
import com.qar.securitysystem.model.UserRole;
import com.qar.securitysystem.repo.FileRecordRepository;
import com.qar.securitysystem.repo.PersonRecordRepository;
import com.qar.securitysystem.repo.UserRepository;
import com.qar.securitysystem.service.FileService;
import com.qar.securitysystem.service.FlightXlsxService;
import com.qar.securitysystem.service.ServerKeyPairService;
import com.qar.securitysystem.util.AesGcmUtil;
import com.qar.securitysystem.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.time.Instant;

@SpringBootTest
class SecuritysystemApplicationTests {
	@Autowired
	private FileService fileService;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private FlightXlsxService flightXlsxService;

	@Autowired
	private FileRecordRepository fileRecordRepository;

	@Autowired
	private PersonRecordRepository personRecordRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ServerKeyPairService serverKeyPairService;

	@Test
	void contextLoads() {
	}

	@Test
	void flightPreviewCanReadPlaintextFromEncryptedStoredFile() throws Exception {
		byte[] xlsxBytes;
		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			var sheet = workbook.createSheet("FlightData");
			var header = sheet.createRow(0);
			header.createCell(0).setCellValue("航班号");
			header.createCell(1).setCellValue("时间");
			var row = sheet.createRow(1);
			row.createCell(0).setCellValue("CA1234");
			row.createCell(1).setCellValue("2026-04-16T08:00:00Z");
			workbook.write(out);
			xlsxBytes = out.toByteArray();
		}

		UserEntity owner = new UserEntity();
		owner.setId("test-user");
		owner.setPersonId("test-person");

		EncryptedFileUploadRequest req = new EncryptedFileUploadRequest();
		req.setEncryptedData(Base64.getEncoder().encodeToString(xlsxBytes));
		req.setOriginalName("flight-preview.xlsx");
		req.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
		req.setSizeBytes((long) xlsxBytes.length);
		req.setPolicy("role:admin");

		FileRecordResponse saved = fileService.storeEncrypted(owner, req);
		FlightXlsxPreviewResponse preview = flightXlsxService.previewFile(saved.getId(), 20);

		Assertions.assertNotNull(preview);
		Assertions.assertFalse(preview.getSheets().isEmpty());
		Assertions.assertEquals("CA1234", String.valueOf(preview.getSheets().get(0).getRows().get(0).get("航班号")));
	}

	@Test
	void labeEnvelopeAllowsMatchingUserAndUsesNewPrefix() throws Exception {
		String personId = ensurePerson("match-user-001", "匹配用户");

		byte[] payload = "policy-protected-data".getBytes();
		UserEntity owner = buildUser("owner-match", personId, UserRole.USER);
		UserEntity viewer = buildUser("viewer-match", personId, UserRole.USER);

		EncryptedFileUploadRequest req = new EncryptedFileUploadRequest();
		req.setEncryptedData(Base64.getEncoder().encodeToString(payload));
		req.setOriginalName("policy.txt");
		req.setContentType("text/plain");
		req.setSizeBytes((long) payload.length);
		req.setPolicy("personNo:match-user-001 AND role:user");

		FileRecordResponse saved = fileService.storeEncrypted(owner, req);
		Assertions.assertEquals("ATTRIBUTE_CONTROLLED", saved.getProtectionStatus());

		FileRecordEntity record = fileRecordRepository.findById(saved.getId()).orElseThrow();
		Assertions.assertTrue(record.getWrappedKey().startsWith("LABE_LATTICE_BC:"));
		String encodedEnvelope = record.getWrappedKey().substring("LABE_LATTICE_BC:".length());
		LatticeAbeService.Envelope envelope = objectMapper.readValue(Base64.getDecoder().decode(encodedEnvelope), LatticeAbeService.Envelope.class);
		Assertions.assertEquals(3, envelope.version);
		Assertions.assertEquals("attribute-policy-user-bound-kyber768-v3", envelope.scheme);
		Assertions.assertTrue(envelope.leaves.stream().allMatch(leaf -> leaf.keyId != null && !leaf.keyId.isBlank()));
		Assertions.assertTrue(envelope.recipients.stream().anyMatch(recipient -> viewer.getId().equals(recipient.recipientId)));
		byte[] decrypted = fileService.decryptForUser(record, viewer, AccessPurpose.USER_PAYLOAD);

		Assertions.assertArrayEquals(payload, decrypted);
	}

	@Test
	void latticeEnvelopeSupportsDepartmentOrRolePolicy() {
		String personId = ensurePerson("dept-user-001", "部门用户");
		byte[] payload = "department-shared".getBytes();
		UserEntity owner = buildUser("dept-owner", personId, UserRole.USER);
		UserEntity viewer = buildUser("dept-viewer", personId, UserRole.USER);

		EncryptedFileUploadRequest req = new EncryptedFileUploadRequest();
		req.setEncryptedData(Base64.getEncoder().encodeToString(payload));
		req.setOriginalName("department.txt");
		req.setContentType("text/plain");
		req.setSizeBytes((long) payload.length);
		req.setPolicy("(department:飞行一部 AND clearanceLevel:l1) OR role:admin");

		FileRecordResponse saved = fileService.storeEncrypted(owner, req);
		Assertions.assertEquals("ATTRIBUTE_CONTROLLED", saved.getProtectionStatus());

		FileRecordEntity record = fileRecordRepository.findById(saved.getId()).orElseThrow();
		Assertions.assertTrue(record.getWrappedKey().startsWith("LABE_LATTICE_BC:"));
		byte[] decrypted = fileService.decryptForUser(record, viewer, AccessPurpose.USER_PAYLOAD);

		Assertions.assertArrayEquals(payload, decrypted);
	}

	@Test
	void labeEnvelopeRejectsUserWhenPolicyNotSatisfied() {
		String ownerPersonId = ensurePerson("policy-owner-001", "策略拥有者");
		String otherPersonId = ensurePerson("policy-other-001", "其他用户");

		byte[] payload = "restricted-data".getBytes();
		UserEntity owner = buildUser("owner-restricted", ownerPersonId, UserRole.USER);

		EncryptedFileUploadRequest req = new EncryptedFileUploadRequest();
		req.setEncryptedData(Base64.getEncoder().encodeToString(payload));
		req.setOriginalName("restricted.txt");
		req.setContentType("text/plain");
		req.setSizeBytes((long) payload.length);
		req.setPolicy("personNo:policy-owner-001 AND role:user");

		FileRecordResponse saved = fileService.storeEncrypted(owner, req);
		FileRecordEntity record = fileRecordRepository.findById(saved.getId()).orElseThrow();
		UserEntity viewer = buildUser("viewer-other", otherPersonId, UserRole.USER);

		Assertions.assertThrows(
				AccessDeniedException.class,
				() -> fileService.decryptForUser(record, viewer, AccessPurpose.USER_PAYLOAD)
		);
	}

	@Test
	void legacyRsaWrappedFileRemainsReadable() {
		String personId = ensurePerson("legacy-user-001", "兼容用户");
		byte[] payload = "legacy-compatible".getBytes();
		javax.crypto.SecretKey aesKey = AesGcmUtil.generateKey();
		byte[] iv = AesGcmUtil.newIv();
		byte[] ciphertext = AesGcmUtil.encrypt(aesKey, iv, payload, "personNo:legacy-user-001".getBytes(java.nio.charset.StandardCharsets.UTF_8));

		FileRecordEntity record = new FileRecordEntity();
		record.setId(IdUtil.newId());
		record.setOwnerId(personId);
		record.setOriginalName("legacy.txt");
		record.setContentType("text/plain");
		record.setSizeBytes(payload.length);
		record.setPolicy("personNo:legacy-user-001");
		record.setWrappedKey(serverKeyPairService.wrapKey(aesKey.getEncoded()));
		record.setEncryptedData(Base64.getEncoder().encodeToString(joinIvAndCiphertext(iv, ciphertext)));
		record.setCreatedAt(Instant.now());
		fileRecordRepository.save(record);

		UserEntity user = buildUser("legacy-viewer", personId, UserRole.USER);
		byte[] decrypted = fileService.decryptForUser(record, user, AccessPurpose.USER_DOWNLOAD);

		Assertions.assertArrayEquals(payload, decrypted);
	}

	@Test
	void rewrapPolicyKeepsEncryptedPayloadAndOnlyRotatesWrappedKey() {
		String oldPersonId = ensurePerson("rewrap-old-001", "原策略用户");
		String newPersonId = ensurePerson("rewrap-new-001", "新策略用户");
		byte[] payload = "rewrap-without-reencrypt".getBytes();
		UserEntity owner = buildUser("rewrap-owner", oldPersonId, UserRole.USER);
		UserEntity oldViewer = buildUser("rewrap-old-viewer", oldPersonId, UserRole.USER);
		UserEntity newViewer = buildUser("rewrap-new-viewer", newPersonId, UserRole.USER);
		String oldPolicy = "personNo:rewrap-old-001";
		String newPolicy = "personNo:rewrap-new-001 OR role:admin";

		EncryptedFileUploadRequest req = new EncryptedFileUploadRequest();
		req.setEncryptedData(Base64.getEncoder().encodeToString(payload));
		req.setOriginalName("rewrap.txt");
		req.setContentType("text/plain");
		req.setSizeBytes((long) payload.length);
		req.setPolicy(oldPolicy);

		FileRecordResponse saved = fileService.storeEncrypted(owner, req);
		FileRecordEntity before = fileRecordRepository.findById(saved.getId()).orElseThrow();
		String encryptedDataBefore = before.getEncryptedData();
		String wrappedKeyBefore = before.getWrappedKey();

		FileRecordResponse updated = fileService.rewrapFilePolicy(saved.getId(), newPolicy);
		FileRecordEntity after = fileRecordRepository.findById(saved.getId()).orElseThrow();

		Assertions.assertEquals(newPolicy, updated.getPolicy());
		Assertions.assertEquals(encryptedDataBefore, after.getEncryptedData());
		Assertions.assertNotEquals(wrappedKeyBefore, after.getWrappedKey());
		Assertions.assertEquals(oldPolicy, after.getAadPolicyBinding());

		Assertions.assertFalse(fileService.canUserAccess(after, oldViewer));
		Assertions.assertTrue(fileService.canUserAccess(after, newViewer));
		Assertions.assertArrayEquals(payload, fileService.decryptForUser(after, newViewer, AccessPurpose.USER_PAYLOAD));
	}

	private String ensurePerson(String personNo, String fullName) {
		return personRecordRepository.findByPersonNo(personNo)
				.map(PersonRecordEntity::getId)
				.orElseGet(() -> {
					PersonRecordEntity person = new PersonRecordEntity();
					person.setId(IdUtil.newId());
					person.setPersonNo(personNo);
					person.setFullName(fullName);
					person.setIdLast4("1234");
					person.setPhone("13800000000");
					person.setDepartment("飞行一部");
					person.setAirline("CAUC");
					person.setPositionTitle("机长");
					person.setPersonCategory("飞行机组");
					person.setDutyDomain("飞行运行");
					person.setFleetGroup("CAUC机队");
					person.setClearanceLevel("L1");
					person.setCreatedAt(Instant.now());
					personRecordRepository.save(person);
					return person.getId();
				});
	}

	private UserEntity buildUser(String id, String personId, UserRole role) {
		UserEntity user = new UserEntity();
		user.setId(id);
		user.setAccount(id);
		user.setPersonId(personId);
		user.setRole(role);
		user.setPasswordHash("test-password-hash");
		user.setAccessEnabled(true);
		user.setCreatedAt(Instant.now());
		return userRepository.save(user);
	}

	private static byte[] joinIvAndCiphertext(byte[] iv, byte[] ciphertext) {
		byte[] combined = new byte[iv.length + ciphertext.length];
		System.arraycopy(iv, 0, combined, 0, iv.length);
		System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
		return combined;
	}

}
