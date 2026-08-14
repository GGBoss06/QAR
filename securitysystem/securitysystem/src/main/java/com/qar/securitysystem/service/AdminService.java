package com.qar.securitysystem.service;

import com.qar.securitysystem.abe.AttributeAuthorityService;
import com.qar.securitysystem.abe.lattice.LatticeUserSecretKeyService;
import com.qar.securitysystem.dto.FileRecordResponse;
import com.qar.securitysystem.dto.FeedbackResponse;
import com.qar.securitysystem.dto.AccountRequestResponse;
import com.qar.securitysystem.dto.AuditLogResponse;
import com.qar.securitysystem.dto.UserResponse;
import com.qar.securitysystem.model.AccountRequestEntity;
import com.qar.securitysystem.model.AccountRequestStatus;
import com.qar.securitysystem.model.AuditLogEntity;
import com.qar.securitysystem.model.FileRecordEntity;
import com.qar.securitysystem.model.FeedbackEntity;
import com.qar.securitysystem.model.FeedbackStatus;
import com.qar.securitysystem.model.PersonRecordEntity;
import com.qar.securitysystem.model.UserEntity;
import com.qar.securitysystem.model.UserRole;
import com.qar.securitysystem.repo.AccountRequestRepository;
import com.qar.securitysystem.repo.AuditLogRepository;
import com.qar.securitysystem.repo.FileRecordRepository;
import com.qar.securitysystem.repo.FeedbackRepository;
import com.qar.securitysystem.repo.PersonRecordRepository;
import com.qar.securitysystem.repo.UserRepository;
import com.qar.securitysystem.util.IdUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
public class AdminService {
    private final UserRepository userRepository;
    private final FileService fileService;
    private final FileRecordRepository fileRecordRepository;
    private final FeedbackRepository feedbackRepository;
    private final PersonRecordRepository personRecordRepository;
    private final AccountRequestRepository accountRequestRepository;
    private final AuditLogRepository auditLogRepository;
    private final AttributeAuthorityService attributeAuthorityService;
    private final LatticeUserSecretKeyService latticeUserSecretKeyService;

    public AdminService(UserRepository userRepository,
                        FileService fileService,
                        FileRecordRepository fileRecordRepository,
                        FeedbackRepository feedbackRepository,
                        PersonRecordRepository personRecordRepository,
                        AccountRequestRepository accountRequestRepository,
                        AuditLogRepository auditLogRepository,
                        AttributeAuthorityService attributeAuthorityService,
                        LatticeUserSecretKeyService latticeUserSecretKeyService) {
        this.userRepository = userRepository;
        this.fileService = fileService;
        this.fileRecordRepository = fileRecordRepository;
        this.feedbackRepository = feedbackRepository;
        this.personRecordRepository = personRecordRepository;
        this.accountRequestRepository = accountRequestRepository;
        this.auditLogRepository = auditLogRepository;
        this.attributeAuthorityService = attributeAuthorityService;
        this.latticeUserSecretKeyService = latticeUserSecretKeyService;
    }

    public List<UserResponse> listUsers() {
        return userRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toUserResponse).toList();
    }

    public List<FileRecordResponse> listAllFiles() {
        List<FileRecordEntity> files = fileRecordRepository.findAllByOrderByCreatedAtDesc();
        Set<String> ownerIds = files.stream()
                .map(FileRecordEntity::getOwnerId)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());

        Map<String, UserEntity> usersById = new HashMap<>();
        userRepository.findAllById(ownerIds).forEach(user -> usersById.put(user.getId(), user));

        Set<String> personIds = new java.util.HashSet<>(ownerIds);
        usersById.values().stream()
                .map(UserEntity::getPersonId)
                .filter(id -> id != null && !id.isBlank())
                .forEach(personIds::add);
        Map<String, PersonRecordEntity> personsById = new HashMap<>();
        personRecordRepository.findAllById(personIds).forEach(person -> personsById.put(person.getId(), person));

        return files.stream()
                .map(file -> toFileResponse(file, usersById, personsById))
                .toList();
    }

    public List<FileRecordEntity> listAllFileEntities() {
        return fileRecordRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<FeedbackResponse> listAllFeedback() {
        return feedbackRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toFeedbackResponse).toList();
    }

    public FeedbackResponse updateFeedback(String id, String status, String adminReply) {
        FeedbackEntity e = feedbackRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("not_found"));
        boolean touched = false;
        if (status != null && !status.isBlank()) {
            FeedbackStatus s = parseStatus(status);
            e.setStatus(s);
            touched = true;
        }
        if (adminReply != null) {
            String r = adminReply.trim();
            if (r.length() > 8000) {
                r = r.substring(0, 8000);
            }
            e.setAdminReply(r.isBlank() ? null : r);
            e.setRepliedAt(r.isBlank() ? null : Instant.now());
            touched = true;
        }
        if (touched) {
            e.setUpdatedAt(Instant.now());
            feedbackRepository.save(e);
        }
        return toFeedbackResponse(e);
    }

    public List<AccountRequestResponse> listPendingAccountRequests() {
        return accountRequestRepository.findAllByStatusOrderByCreatedAtDesc(AccountRequestStatus.PENDING).stream().map(this::toAccountRequestResponse).toList();
    }

    @Transactional
    public AccountRequestResponse approveAccountRequest(String id, String adminId, String adminNote) {
        AccountRequestEntity e = accountRequestRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("not_found"));
        if (e.getStatus() != AccountRequestStatus.PENDING) {
            throw new IllegalArgumentException("request_not_pending");
        }
        if (userRepository.existsByAccount(e.getPersonNo())) {
            throw new IllegalArgumentException("user_already_exists");
        }
        PersonRecordEntity pr = personRecordRepository.findByPersonNo(e.getPersonNo()).orElse(null);
        if (pr == null) {
            throw new IllegalArgumentException("profile_not_found");
        }
        UserEntity u = new UserEntity();
        u.setId(IdUtil.newId());
        u.setAccount(e.getPersonNo());
        u.setPasswordHash(e.getPasswordHash());
        u.setPersonId(pr.getId());
        u.setRole(UserRole.USER);
        u.setCreatedAt(Instant.now());
        u.setPublicKey(e.getPublicKey());
        u.setAccessEnabled(true);
        userRepository.save(u);
        latticeUserSecretKeyService.issueForUser(u, "account_approved");
        fileService.refreshAllPolicyRecipients();

        e.setStatus(AccountRequestStatus.APPROVED);
        e.setReviewedAt(Instant.now());
        e.setReviewedByAdminId(adminId);
        e.setAdminNote(trimNote(adminNote));
        accountRequestRepository.save(e);
        return toAccountRequestResponse(e);
    }

    public AccountRequestResponse rejectAccountRequest(String id, String adminId, String adminNote) {
        AccountRequestEntity e = accountRequestRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("not_found"));
        if (e.getStatus() != AccountRequestStatus.PENDING) {
            throw new IllegalArgumentException("request_not_pending");
        }
        e.setStatus(AccountRequestStatus.REJECTED);
        e.setReviewedAt(Instant.now());
        e.setReviewedByAdminId(adminId);
        e.setAdminNote(trimNote(adminNote));
        accountRequestRepository.save(e);
        return toAccountRequestResponse(e);
    }

    public List<AuditLogResponse> listAuditLogs() {
        return auditLogRepository.findTop200ByOrderByCreatedAtDesc().stream().map(this::toAuditLogResponse).toList();
    }

    public List<PersonRecordEntity> listAllPersons() {
        return personRecordRepository.findAllByOrderByPersonNoAsc();
    }

    public PersonRecordEntity createPerson(PersonRecordEntity person) {
        if (person.getPersonNo() == null || person.getPersonNo().isBlank()) {
            throw new IllegalArgumentException("person_no_required");
        }
        if (personRecordRepository.existsByPersonNo(person.getPersonNo())) {
            throw new IllegalArgumentException("person_no_already_exists");
        }
        person.setId(IdUtil.newId());
        person.setCreatedAt(Instant.now());
        return personRecordRepository.save(person);
    }

    @Transactional
    public PersonRecordEntity updatePerson(String id, PersonRecordEntity person) {
        PersonRecordEntity existing = personRecordRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("not_found"));
        Set<String> beforeAttributes = attributeAuthorityService.resolvePersonAttributes(existing);
        if (person.getFullName() != null) existing.setFullName(person.getFullName());
        if (person.getPhone() != null) existing.setPhone(person.getPhone());
        if (person.getDepartment() != null) existing.setDepartment(person.getDepartment());
        if (person.getAirline() != null) existing.setAirline(person.getAirline());
        if (person.getPositionTitle() != null) existing.setPositionTitle(person.getPositionTitle());
        if (person.getPersonCategory() != null) existing.setPersonCategory(person.getPersonCategory());
        if (person.getDutyDomain() != null) existing.setDutyDomain(person.getDutyDomain());
        if (person.getFleetGroup() != null) existing.setFleetGroup(person.getFleetGroup());
        if (person.getClearanceLevel() != null) existing.setClearanceLevel(person.getClearanceLevel());
        if (person.getIdLast4() != null) existing.setIdLast4(person.getIdLast4());
        PersonRecordEntity saved = personRecordRepository.save(existing);
        Set<String> afterAttributes = attributeAuthorityService.resolvePersonAttributes(saved);
        if (!beforeAttributes.equals(afterAttributes)) {
            userRepository.findByPersonId(saved.getId())
                    .ifPresent(user -> latticeUserSecretKeyService.issueForUser(user, "person_attributes_updated"));
            fileService.refreshAllPolicyRecipients();
        }
        return saved;
    }

    public void deletePerson(String id) {
        if (!personRecordRepository.existsById(id)) {
            throw new IllegalArgumentException("not_found");
        }
        personRecordRepository.deleteById(id);
    }

    public void deleteFile(String id) {
        if (!fileRecordRepository.existsById(id)) {
            throw new IllegalArgumentException("not_found");
        }
        fileRecordRepository.deleteById(id);
    }

    public FileRecordResponse rewrapFilePolicy(String id, String policy) {
        return fileService.rewrapFilePolicy(id, policy);
    }

    @Transactional
    public LatticeUserSecretKeyService.UserSecretBundle issueLatticeBundleForPerson(String personId, String reason) {
        PersonRecordEntity person = personRecordRepository.findById(personId).orElseThrow(() -> new IllegalArgumentException("not_found"));
        UserEntity user = userRepository.findByPersonId(person.getId()).orElseThrow(() -> new IllegalArgumentException("account_not_ready"));
        LatticeUserSecretKeyService.UserSecretBundle bundle = latticeUserSecretKeyService.issueForUser(user, reason == null || reason.isBlank() ? "admin_manual_issue" : reason.trim());
        fileService.refreshAllPolicyRecipients();
        return bundle;
    }

    @Transactional
    public void freezeLatticeAccessForPerson(String personId, String reason) {
        PersonRecordEntity person = personRecordRepository.findById(personId).orElseThrow(() -> new IllegalArgumentException("not_found"));
        UserEntity user = userRepository.findByPersonId(person.getId()).orElseThrow(() -> new IllegalArgumentException("account_not_ready"));
        if (user.getRole() == UserRole.ADMIN) {
            throw new IllegalArgumentException("admin_access_cannot_be_frozen");
        }
        user.setAccessEnabled(false);
        user.setAccessRevokedAt(Instant.now());
        user.setAccessRevokedReason(normalizeAccessReason(reason, "admin_access_frozen"));
        userRepository.save(user);
        latticeUserSecretKeyService.revokeActiveBundle(user.getId(), user.getAccessRevokedReason());
        fileService.refreshAllPolicyRecipients();
    }

    @Transactional
    public LatticeUserSecretKeyService.UserSecretBundle restoreLatticeAccessForPerson(String personId, String reason) {
        PersonRecordEntity person = personRecordRepository.findById(personId).orElseThrow(() -> new IllegalArgumentException("not_found"));
        UserEntity user = userRepository.findByPersonId(person.getId()).orElseThrow(() -> new IllegalArgumentException("account_not_ready"));
        user.setAccessEnabled(true);
        user.setAccessRevokedAt(null);
        user.setAccessRevokedReason(null);
        userRepository.save(user);
        LatticeUserSecretKeyService.UserSecretBundle bundle = latticeUserSecretKeyService.issueForUser(user, normalizeAccessReason(reason, "admin_access_restored"));
        fileService.refreshAllPolicyRecipients();
        return bundle;
    }

    private UserResponse toUserResponse(UserEntity u) {
        UserResponse resp = new UserResponse();
        resp.setId(u.getId());
        resp.setEmailOrUsername(u.getAccount());
        resp.setRole(u.getRole() == null ? null : u.getRole().name().toLowerCase());
        resp.setCreatedAt(u.getCreatedAt() == null ? null : u.getCreatedAt().toString());
        return resp;
    }

    private FileRecordResponse toFileResponse(FileRecordEntity r,
                                              Map<String, UserEntity> usersById,
                                              Map<String, PersonRecordEntity> personsById) {
        FileRecordResponse resp = new FileRecordResponse();
        resp.setId(r.getId());
        resp.setOwnerId(r.getOwnerId());
        UserEntity ownerUser = usersById.get(r.getOwnerId());
        PersonRecordEntity pr;
        if (ownerUser != null && ownerUser.getPersonId() != null) {
            pr = personsById.get(ownerUser.getPersonId());
        } else {
            pr = personsById.get(r.getOwnerId());
        }
        if (pr != null) {
            resp.setOwnerLabel(pr.getPersonNo() + " " + pr.getFullName());
        }
        resp.setOriginalName(r.getOriginalName());
        resp.setContentType(r.getContentType());
        resp.setSizeBytes(r.getSizeBytes());
        resp.setPolicy(r.getPolicy());
        resp.setProtectionStatus(FileProtectionStatus.fromWrappedKey(r.getWrappedKey()));
        resp.setCreatedAt(r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        return resp;
    }

    private FeedbackResponse toFeedbackResponse(FeedbackEntity e) {
        return com.qar.securitysystem.service.FeedbackService.toResponse(e);
    }

    private AccountRequestResponse toAccountRequestResponse(AccountRequestEntity e) {
        AccountRequestResponse r = new AccountRequestResponse();
        r.setId(e.getId());
        r.setPersonNo(e.getPersonNo());
        r.setFullName(e.getFullName());
        r.setAirline(e.getAirline());
        r.setPositionTitle(e.getPositionTitle());
        r.setDepartment(e.getDepartment());
        r.setContact(e.getContact());
        r.setStatus(e.getStatus() == null ? null : e.getStatus().name().toLowerCase());
        r.setCreatedAt(e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        r.setReviewedAt(e.getReviewedAt() == null ? null : e.getReviewedAt().toString());
        r.setAdminNote(e.getAdminNote());
        return r;
    }

    private String trimNote(String note) {
        if (note == null) {
            return null;
        }
        String v = note.trim();
        if (v.isBlank()) {
            return null;
        }
        if (v.length() > 400) {
            v = v.substring(0, 400);
        }
        return v;
    }

    private String normalizeAccessReason(String note, String fallback) {
        if (note == null || note.isBlank()) {
            return fallback;
        }
        String value = note.trim();
        if (value.length() > 240) {
            value = value.substring(0, 240);
        }
        return value;
    }

    private AuditLogResponse toAuditLogResponse(AuditLogEntity e) {
        AuditLogResponse r = new AuditLogResponse();
        r.setId(e.getId());
        r.setPersonNo(e.getPersonNo());
        r.setMethod(e.getMethod());
        r.setPath(e.getPath());
        r.setStatusCode(e.getStatusCode());
        r.setDurationMs(e.getDurationMs());
        r.setIp(e.getIp());
        r.setUserAgent(e.getUserAgent());
        r.setCreatedAt(e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        return r;
    }

    private FeedbackStatus parseStatus(String s) {
        String v = s.trim().toUpperCase().replace("-", "_");
        try {
            return FeedbackStatus.valueOf(v);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid_status");
        }
    }
}
