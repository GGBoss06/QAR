package com.qar.securitysystem.controller;

import com.qar.securitysystem.dto.AdminFeedbackUpdateRequest;
import com.qar.securitysystem.dto.AdminAccountRequestReview;
import com.qar.securitysystem.dto.AdminFilePolicyUpdateRequest;
import com.qar.securitysystem.dto.AdminReasonRequest;
import com.qar.securitysystem.model.FileRecordEntity;
import com.qar.securitysystem.model.PersonRecordEntity;
import com.qar.securitysystem.repo.PersonRecordRepository;
import com.qar.securitysystem.security.AppPrincipal;
import com.qar.securitysystem.service.AdminService;
import com.qar.securitysystem.service.FileService;
import com.qar.securitysystem.service.LabeAdminService;
import com.qar.securitysystem.abe.lattice.LatticeUserSecretKeyService;
import com.qar.securitysystem.util.SecurityUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService adminService;
    private final FileService fileService;
    private final PersonRecordRepository personRecordRepository;
    private final LabeAdminService labeAdminService;

    public AdminController(AdminService adminService, FileService fileService, PersonRecordRepository personRecordRepository, LabeAdminService labeAdminService) {
        this.adminService = adminService;
        this.fileService = fileService;
        this.personRecordRepository = personRecordRepository;
        this.labeAdminService = labeAdminService;
    }

    @GetMapping("/users")
    public ResponseEntity<?> users() {
        return ResponseEntity.ok(adminService.listUsers());
    }

    @GetMapping("/persons")
    public ResponseEntity<?> persons() {
        return ResponseEntity.ok(adminService.listAllPersons());
    }

    @PostMapping("/persons")
    public ResponseEntity<?> createPerson(@RequestBody PersonRecordEntity person) {
        try {
            return ResponseEntity.ok(adminService.createPerson(person));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("code", 400, "message", e.getMessage()));
        }
    }

    @PutMapping("/persons/{id}")
    public ResponseEntity<?> updatePerson(@PathVariable("id") String id, @RequestBody PersonRecordEntity person) {
        try {
            return ResponseEntity.ok(adminService.updatePerson(id, person));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("code", 400, "message", e.getMessage()));
        }
    }

    @DeleteMapping("/persons/{id}")
    public ResponseEntity<?> deletePerson(@PathVariable("id") String id) {
        try {
            adminService.deletePerson(id);
            return ResponseEntity.ok(java.util.Map.of("code", 200, "message", "ok"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("code", 400, "message", e.getMessage()));
        }
    }

    @GetMapping("/files")
    public ResponseEntity<?> files() {
        return ResponseEntity.ok(adminService.listAllFiles());
    }

    @GetMapping("/labe/overview")
    public ResponseEntity<?> labeOverview() {
        return ResponseEntity.ok(labeAdminService.getOverview());
    }

    @GetMapping("/labe/authorities")
    public ResponseEntity<?> labeAuthorities() {
        return ResponseEntity.ok(labeAdminService.listAuthorities());
    }

    @GetMapping("/labe/persons")
    public ResponseEntity<?> labePersons() {
        return ResponseEntity.ok(labeAdminService.listPersonViews());
    }

    @PostMapping("/labe/persons/{id}/issue")
    public ResponseEntity<?> issueLabeBundle(@PathVariable("id") String id) {
        try {
            return ResponseEntity.ok(toBundleSummary(adminService.issueLatticeBundleForPerson(id, "admin_manual_issue")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("code", 400, "message", e.getMessage()));
        }
    }

    @PostMapping("/labe/persons/{id}/freeze")
    public ResponseEntity<?> freezeLabeAccess(@PathVariable("id") String id, @RequestBody(required = false) AdminReasonRequest req) {
        try {
            adminService.freezeLatticeAccessForPerson(id, req == null ? null : req.getReason());
            return ResponseEntity.ok(java.util.Map.of("code", 200, "message", "ok"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("code", 400, "message", e.getMessage()));
        }
    }

    @PostMapping("/labe/persons/{id}/restore")
    public ResponseEntity<?> restoreLabeAccess(@PathVariable("id") String id, @RequestBody(required = false) AdminReasonRequest req) {
        try {
            return ResponseEntity.ok(toBundleSummary(adminService.restoreLatticeAccessForPerson(id, req == null ? null : req.getReason())));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("code", 400, "message", e.getMessage()));
        }
    }

    @DeleteMapping("/files/{id}")
    public ResponseEntity<?> deleteFile(@PathVariable("id") String id) {
        try {
            adminService.deleteFile(id);
            return ResponseEntity.ok(java.util.Map.of("code", 200, "message", "ok"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("code", 400, "message", e.getMessage()));
        }
    }

    @PatchMapping("/files/{id}/policy")
    public ResponseEntity<?> rewrapFilePolicy(@PathVariable("id") String id, @RequestBody AdminFilePolicyUpdateRequest req) {
        try {
            return ResponseEntity.ok(adminService.rewrapFilePolicy(id, req == null ? null : req.getPolicy()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("code", 400, "message", e.getMessage()));
        }
    }

    @GetMapping("/files/export")
    public ResponseEntity<byte[]> exportAll() {
        List<FileRecordEntity> all = adminService.listAllFileEntities();
        byte[] zip = zipAll(all);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"all-data.zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(zip);
    }

    @GetMapping("/feedback")
    public ResponseEntity<?> feedback() {
        return ResponseEntity.ok(adminService.listAllFeedback());
    }

    @GetMapping("/account-requests")
    public ResponseEntity<?> accountRequests() {
        return ResponseEntity.ok(adminService.listPendingAccountRequests());
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<?> auditLogs() {
        return ResponseEntity.ok(adminService.listAuditLogs());
    }

    @PostMapping("/account-requests/{id}/approve")
    public ResponseEntity<?> approve(Authentication authentication, @PathVariable("id") String id, @RequestBody(required = false) AdminAccountRequestReview req) {
        try {
            AppPrincipal p = SecurityUtil.requirePrincipal(authentication);
            return ResponseEntity.ok(adminService.approveAccountRequest(id, p.getUserId(), req == null ? null : req.getAdminNote()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("code", 400, "message", e.getMessage()));
        }
    }

    @PostMapping("/account-requests/{id}/reject")
    public ResponseEntity<?> reject(Authentication authentication, @PathVariable("id") String id, @RequestBody(required = false) AdminAccountRequestReview req) {
        try {
            AppPrincipal p = SecurityUtil.requirePrincipal(authentication);
            return ResponseEntity.ok(adminService.rejectAccountRequest(id, p.getUserId(), req == null ? null : req.getAdminNote()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("code", 400, "message", e.getMessage()));
        }
    }

    @PatchMapping("/feedback/{id}")
    public ResponseEntity<?> updateFeedback(@PathVariable("id") String id, @RequestBody AdminFeedbackUpdateRequest req) {
        try {
            return ResponseEntity.ok(adminService.updateFeedback(id, req == null ? null : req.getStatus(), req == null ? null : req.getAdminReply()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("code", 400, "message", e.getMessage()));
        }
    }

    private byte[] zipAll(List<FileRecordEntity> all) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ZipOutputStream zos = new ZipOutputStream(bos);
            for (FileRecordEntity r : all) {
                byte[] raw = fileService.decryptForAdminExport(r);
                String entryName = r.getOwnerId() + "/" + r.getId() + "_" + safeFilename(r.getOriginalName());
                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);
                zos.write(raw);
                zos.closeEntry();
            }
            zos.finish();
            zos.close();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("export_failed", e);
        }
    }

    private static String safeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "download.bin";
        }
        return name.replace("\\", "_").replace("/", "_").replace("\n", " ").replace("\r", " ");
    }

    private static java.util.Map<String, Object> toBundleSummary(LatticeUserSecretKeyService.UserSecretBundle bundle) {
        if (bundle == null) {
            return java.util.Map.of();
        }
        java.util.Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("bundleId", bundle.bundleId);
        summary.put("userId", bundle.userId);
        summary.put("bundleVersion", bundle.bundleVersion);
        summary.put("status", bundle.status);
        summary.put("keyScheme", bundle.keyScheme);
        summary.put("issuedReason", bundle.issuedReason);
        summary.put("issuedAt", bundle.issuedAt);
        summary.put("attributeCount", bundle.attributes == null ? 0 : bundle.attributes.size());
        return summary;
    }
}
