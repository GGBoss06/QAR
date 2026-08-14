package com.qar.securitysystem.service;

import com.qar.securitysystem.abe.AccessPurpose;
import com.qar.securitysystem.abe.AttributeAuthorityService;
import com.qar.securitysystem.abe.FileKeyEnvelopeService;
import com.qar.securitysystem.abe.lattice.LatticeUserSecretKeyService;
import com.qar.securitysystem.dto.EncryptedFileResponse;
import com.qar.securitysystem.dto.EncryptedFileUploadRequest;
import com.qar.securitysystem.dto.FileRecordResponse;
import com.qar.securitysystem.config.FileStorageProperties;
import com.qar.securitysystem.model.FileRecordEntity;
import com.qar.securitysystem.model.UserEntity;
import com.qar.securitysystem.repo.FileRecordRepository;
import com.qar.securitysystem.util.AesGcmUtil;
import com.qar.securitysystem.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;

@Service
public class FileService {
    private static final String LEGACY_PLAIN = "PLAIN_TEXT";
    private static final int GCM_IV_LENGTH = 12;

    private final FileRecordRepository fileRecordRepository;
    private final ServerKeyPairService serverKeyPairService;
    private final FileKeyEnvelopeService fileKeyEnvelopeService;
    private final AttributeAuthorityService attributeAuthorityService;
    private final FileStorageProperties fileStorageProperties;

    public FileService(FileRecordRepository fileRecordRepository, ServerKeyPairService serverKeyPairService, FileKeyEnvelopeService fileKeyEnvelopeService, AttributeAuthorityService attributeAuthorityService, FileStorageProperties fileStorageProperties) {
        this.fileRecordRepository = fileRecordRepository;
        this.serverKeyPairService = serverKeyPairService;
        this.fileKeyEnvelopeService = fileKeyEnvelopeService;
        this.attributeAuthorityService = attributeAuthorityService;
        this.fileStorageProperties = fileStorageProperties;
    }

    public FileRecordResponse uploadAndEncrypt(UserEntity user, MultipartFile file, String policy) {
        if (file.getSize() > fileStorageProperties.getMaxBytes()) {
            throw new IllegalArgumentException("file_too_large");
        }
        byte[] raw;
        try {
            raw = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("file_read_failed", e);
        }

        StoredFilePayload stored = encryptForStorage(raw, policy);

        FileRecordEntity r = new FileRecordEntity();
        r.setId(IdUtil.newId());
        r.setOwnerId(resolveBusinessOwner(user));
        r.setOriginalName(file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename());
        r.setContentType(file.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.getContentType());
        r.setSizeBytes(raw.length);
        r.setPolicy(policy);
        r.setAadPolicyBinding(policy);
        r.setWrappedKey(stored.wrappedKey());
        r.setEncryptedData(stored.encryptedDataBase64());
        r.setCreatedAt(Instant.now());

        fileRecordRepository.save(r);
        return toResponse(r);
    }

    public FileRecordResponse storeEncrypted(UserEntity user, EncryptedFileUploadRequest request) {
        if (request.getEncryptedData() == null || encodedPayloadExceedsLimit(request.getEncryptedData())) {
            throw new IllegalArgumentException("file_too_large");
        }
        byte[] plainData;
        try {
            plainData = Base64.getDecoder().decode(request.getEncryptedData());
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid_file_payload", e);
        }

        StoredFilePayload stored = encryptForStorage(plainData, request.getPolicy());
        FileRecordEntity r = new FileRecordEntity();
        r.setId(IdUtil.newId());
        r.setOwnerId(resolveBusinessOwner(user));
        r.setOriginalName(normalizeFilename(request.getOriginalName()));
        r.setContentType(request.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : request.getContentType());
        r.setSizeBytes(plainData.length);
        r.setPolicy(request.getPolicy());
        r.setAadPolicyBinding(request.getPolicy());
        r.setWrappedKey(stored.wrappedKey());
        r.setEncryptedData(stored.encryptedDataBase64());
        r.setCreatedAt(Instant.now());

        fileRecordRepository.save(r);
        // #region debug-point B:store-encrypted
        debugReport("B", "FileService.storeEncrypted", "[DEBUG] stored encrypted file", Map.of(
                "recordId", safe(r.getId()),
                "ownerId", safe(r.getOwnerId()),
                "policy", safe(r.getPolicy()),
                "originalName", safe(r.getOriginalName()),
                "uploaderId", user == null ? "" : safe(user.getId()),
                "uploaderAccount", user == null ? "" : safe(user.getAccount()),
                "wrappedKeyPrefix", prefix(r.getWrappedKey())
        ));
        // #endregion
        return toResponse(r);
    }

    public List<FileRecordResponse> listMine(List<String> ownerIds) {
        return fileRecordRepository.findAllByOwnerIdInOrderByCreatedAtDesc(ownerIds).stream().map(this::toResponse).toList();
    }

    public List<FileRecordResponse> listAccessible(UserEntity user) {
        return fileRecordRepository.findAll().stream()
                .filter(record -> canUserAccess(record, user))
                .sorted((a, b) -> {
                    Instant left = a.getCreatedAt() == null ? Instant.EPOCH : a.getCreatedAt();
                    Instant right = b.getCreatedAt() == null ? Instant.EPOCH : b.getCreatedAt();
                    return right.compareTo(left);
                })
                .map(this::toResponse)
                .toList();
    }

    public List<FileRecordResponse> listAll() {
        return fileRecordRepository.findAll().stream().map(this::toResponse).toList();
    }

    public FileRecordEntity getRecordOrNull(String id) {
        return fileRecordRepository.findById(id).orElse(null);
    }

    public boolean canUserAccess(FileRecordEntity record, UserEntity user) {
        if (record == null || user == null) {
            return false;
        }
        if (user.getRole() != null && user.getRole().name().equals("ADMIN")) {
            return true;
        }
        if (!LatticeUserSecretKeyService.isUserAccessEnabled(user)) {
            return false;
        }
        if (isBusinessOwner(record, user)) {
            // #region debug-point A:owner-access
            debugReport("A", "FileService.canUserAccess", "[DEBUG] owner access granted", Map.of(
                    "recordId", safe(record.getId()),
                    "userId", safe(user.getId()),
                    "account", safe(user.getAccount()),
                    "ownerId", safe(record.getOwnerId()),
                    "policy", safe(record.getPolicy())
            ));
            // #endregion
            return true;
        }
        boolean allowed = attributeAuthorityService.canUserAccess(record.getPolicy(), user);
        // #region debug-point A:policy-access
        debugReport("A", "FileService.canUserAccess", "[DEBUG] evaluated policy access", Map.of(
                "recordId", safe(record.getId()),
                "userId", safe(user.getId()),
                "account", safe(user.getAccount()),
                "ownerId", safe(record.getOwnerId()),
                "policy", safe(record.getPolicy()),
                "allowed", allowed
        ));
        // #endregion
        return allowed;
    }

    public long countOwnedBy(UserEntity user) {
        if (user == null || user.getId() == null || user.getId().isBlank()) {
            return 0L;
        }
        return fileRecordRepository.countByOwnerId(user.getId());
    }

    public long countAccessibleBy(UserEntity user) {
        if (user == null) {
            return 0L;
        }
        List<FileRecordEntity> all = fileRecordRepository.findAll();
        long count = all.stream().filter(record -> canUserAccess(record, user)).count();
        // #region debug-point E:accessible-count
        debugReport("E", "FileService.countAccessibleBy", "[DEBUG] counted accessible files", Map.of(
                "userId", safe(user.getId()),
                "account", safe(user.getAccount()),
                "personId", safe(user.getPersonId()),
                "totalRecords", all.size(),
                "accessibleCount", count
        ));
        // #endregion
        return count;
    }

    public byte[] decryptForDownload(FileRecordEntity record) {
        return decryptStoredData(record, null, AccessPurpose.SYSTEM_INTERNAL);
    }

    public byte[] decryptForAdminPreview(FileRecordEntity record) {
        return decryptStoredData(record, null, AccessPurpose.ADMIN_PREVIEW);
    }

    public byte[] decryptForAdminExport(FileRecordEntity record) {
        return decryptStoredData(record, null, AccessPurpose.ADMIN_EXPORT);
    }

    public byte[] decryptForUser(FileRecordEntity record, UserEntity user, AccessPurpose purpose) {
        return decryptStoredData(record, user, purpose);
    }

    public EncryptedFileResponse getEncryptedDataForUser(FileRecordEntity record, UserEntity user) {
        return getEncryptedDataForUser(record, user, AccessPurpose.USER_DOWNLOAD);
    }

    public EncryptedFileResponse getEncryptedDataForUser(FileRecordEntity record, UserEntity user, AccessPurpose purpose) {
        if (user.getPublicKey() == null || user.getPublicKey().isBlank()) {
            throw new IllegalStateException("user_public_key_missing");
        }

        byte[] plainData = decryptStoredData(record, user, purpose);
        try {
            javax.crypto.SecretKey aesKey = AesGcmUtil.generateKey();
            byte[] iv = AesGcmUtil.newIv();
            byte[] encryptedData = AesGcmUtil.encrypt(aesKey, iv, plainData, buildFileAad(record.getPolicy()));

            EncryptedFileResponse resp = new EncryptedFileResponse();
            resp.setEncryptedData(Base64.getEncoder().encodeToString(joinIvAndCiphertext(iv, encryptedData)));
            resp.setWrappedKey(serverKeyPairService.wrapKeyForPublicKey(aesKey.getEncoded(), user.getPublicKey()));
            resp.setOriginalName(record.getOriginalName());
            resp.setContentType(record.getContentType());
            resp.setPolicy(record.getPolicy());
            return resp;
        } catch (Exception e) {
            throw new RuntimeException("failed_to_encrypt_for_transmission", e);
        }
    }

    public EncryptedFileResponse getEncryptedData(FileRecordEntity record) {
        // This is the old method, we should probably not use it if we want transmission encryption
        EncryptedFileResponse resp = new EncryptedFileResponse();
        resp.setEncryptedData(record.getEncryptedData());
        resp.setWrappedKey(record.getWrappedKey());
        resp.setOriginalName(record.getOriginalName());
        resp.setContentType(record.getContentType());
        resp.setPolicy(record.getPolicy());
        return resp;
    }

    @Transactional
    public FileRecordResponse rewrapFilePolicy(String fileId, String newPolicy) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("file_id_required");
        }
        String normalizedPolicy = normalizePolicy(newPolicy);
        FileRecordEntity record = fileRecordRepository.findById(fileId).orElseThrow(() -> new IllegalArgumentException("not_found"));
        String oldPolicy = normalizePolicy(record.getPolicy());
        if (oldPolicy.equals(normalizedPolicy)) {
            throw new IllegalArgumentException("policy_not_changed");
        }
        byte[] fileKey = fileKeyEnvelopeService.unwrapForSystem(record, AccessPurpose.SYSTEM_INTERNAL);
        String aadBinding = normalizeAadBinding(record);
        record.setAadPolicyBinding(aadBinding);
        record.setPolicy(normalizedPolicy);
        record.setWrappedKey(fileKeyEnvelopeService.wrapForStorage(fileKey, normalizedPolicy));
        fileRecordRepository.save(record);
        return toResponse(record);
    }

    @Transactional
    public int refreshAllPolicyRecipients() {
        List<FileRecordEntity> changed = new ArrayList<>();
        for (FileRecordEntity record : fileRecordRepository.findAll()) {
            if (!fileKeyEnvelopeService.isLatticeEnvelope(record.getWrappedKey())) {
                continue;
            }
            byte[] fileKey = fileKeyEnvelopeService.unwrapForSystem(record, AccessPurpose.SYSTEM_INTERNAL);
            try {
                record.setWrappedKey(fileKeyEnvelopeService.wrapForStorage(fileKey, record.getPolicy()));
                changed.add(record);
            } finally {
                Arrays.fill(fileKey, (byte) 0);
            }
        }
        if (!changed.isEmpty()) {
            fileRecordRepository.saveAll(changed);
        }
        return changed.size();
    }

    private FileRecordResponse toResponse(FileRecordEntity r) {
        FileRecordResponse resp = new FileRecordResponse();
        resp.setId(r.getId());
        resp.setOwnerId(r.getOwnerId());
        resp.setOriginalName(normalizeFilename(r.getOriginalName()));
        resp.setContentType(r.getContentType());
        resp.setSizeBytes(r.getSizeBytes());
        resp.setPolicy(r.getPolicy());
        resp.setProtectionStatus(FileProtectionStatus.fromWrappedKey(r.getWrappedKey()));
        resp.setCreatedAt(r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        return resp;
    }

    private static String normalizeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "data.bin";
        }
        String cleaned = name.replace("\u0000", "").replace("\r", " ").replace("\n", " ").trim();
        if (cleaned.matches("(?i)^QAR\\?+\\.xlsx$")) {
            return "QAR示例数据.xlsx";
        }
        if (!cleaned.contains("?")) {
            return cleaned;
        }
        try {
            byte[] bytes = cleaned.getBytes(StandardCharsets.ISO_8859_1);
            String utf8 = new String(bytes, StandardCharsets.UTF_8).trim();
            if (!utf8.isBlank() && !utf8.contains("�")) {
                return utf8;
            }
        } catch (Exception e) {
        }
        return cleaned;
    }

    private StoredFilePayload encryptForStorage(byte[] plainData, String policy) {
        javax.crypto.SecretKey aesKey = AesGcmUtil.generateKey();
        byte[] iv = AesGcmUtil.newIv();
        byte[] ciphertext = AesGcmUtil.encrypt(aesKey, iv, plainData, buildFileAad(policy));
        return new StoredFilePayload(
                Base64.getEncoder().encodeToString(joinIvAndCiphertext(iv, ciphertext)),
                fileKeyEnvelopeService.wrapForStorage(aesKey.getEncoded(), policy)
        );
    }

    private byte[] decryptStoredData(FileRecordEntity record, UserEntity user, AccessPurpose purpose) {
        if (record == null || record.getEncryptedData() == null || record.getEncryptedData().isBlank()) {
            return new byte[0];
        }
        if (record.getWrappedKey() == null || record.getWrappedKey().isBlank() || LEGACY_PLAIN.equals(record.getWrappedKey())) {
            return Base64.getDecoder().decode(record.getEncryptedData());
        }

        byte[] keyBytes = user == null
                ? fileKeyEnvelopeService.unwrapForSystem(record, purpose)
                : fileKeyEnvelopeService.unwrapForUser(record, user, purpose);
        SecretKeySpec aesKey = new SecretKeySpec(keyBytes, "AES");
        byte[] ivAndCiphertext = Base64.getDecoder().decode(record.getEncryptedData());
        if (ivAndCiphertext.length < GCM_IV_LENGTH) {
            throw new IllegalArgumentException("invalid_stored_ciphertext");
        }
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] ciphertext = new byte[ivAndCiphertext.length - GCM_IV_LENGTH];
        System.arraycopy(ivAndCiphertext, 0, iv, 0, iv.length);
        System.arraycopy(ivAndCiphertext, iv.length, ciphertext, 0, ciphertext.length);
        return AesGcmUtil.decrypt(aesKey, iv, ciphertext, buildFileAad(normalizeAadBinding(record)));
    }

    private static byte[] buildFileAad(String policy) {
        String safePolicy = policy == null ? "" : policy.trim();
        return safePolicy.getBytes(StandardCharsets.UTF_8);
    }

    private static String normalizePolicy(String policy) {
        String value = policy == null ? "" : policy.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("policy_required");
        }
        return value;
    }

    private static String normalizeAadBinding(FileRecordEntity record) {
        if (record == null) {
            return "";
        }
        String binding = record.getAadPolicyBinding();
        if (binding != null && !binding.isBlank()) {
            return binding.trim();
        }
        return record.getPolicy() == null ? "" : record.getPolicy().trim();
    }

    private static String resolveBusinessOwner(UserEntity user) {
        if (user == null || user.getId() == null || user.getId().isBlank()) {
            return "system";
        }
        return user.getId();
    }

    private static boolean isBusinessOwner(FileRecordEntity record, UserEntity user) {
        return record != null
                && user != null
                && user.getId() != null
                && !user.getId().isBlank()
                && user.getId().equals(record.getOwnerId());
    }

    private static byte[] joinIvAndCiphertext(byte[] iv, byte[] ciphertext) {
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
        return combined;
    }

    private boolean encodedPayloadExceedsLimit(String encodedData) {
        long maxEncodedLength = ((fileStorageProperties.getMaxBytes() + 2L) / 3L) * 4L;
        return encodedData.length() > maxEncodedLength;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String prefix(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= 32 ? value : value.substring(0, 32);
    }

    private static void debugReport(String hypothesisId, String location, String msg, Map<String, Object> data) {
        // Legacy debug forwarding is intentionally disabled.
    }

    private record StoredFilePayload(String encryptedDataBase64, String wrappedKey) {
    }
}
