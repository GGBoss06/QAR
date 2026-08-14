package com.qar.securitysystem.controller;

import com.qar.securitysystem.abe.AccessPurpose;
import com.qar.securitysystem.dto.EncryptedFileResponse;
import com.qar.securitysystem.dto.EncryptedFileUploadRequest;
import com.qar.securitysystem.dto.FileRecordResponse;
import com.qar.securitysystem.dto.PlainFilePayloadResponse;
import com.qar.securitysystem.model.FileRecordEntity;
import com.qar.securitysystem.model.PersonRecordEntity;
import com.qar.securitysystem.model.UserEntity;
import com.qar.securitysystem.repo.FileRecordRepository;
import com.qar.securitysystem.repo.PersonRecordRepository;
import com.qar.securitysystem.repo.UserRepository;
import com.qar.securitysystem.security.AppPrincipal;
import com.qar.securitysystem.service.FileService;
import com.qar.securitysystem.startup.PersonSeeder;
import com.qar.securitysystem.util.SecurityUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FilesController {
    private final FileService fileService;
    private final UserRepository userRepository;
    private final PersonRecordRepository personRecordRepository;
    private final FileRecordRepository fileRecordRepository;
    private final PersonSeeder personSeeder;

    public FilesController(FileService fileService, UserRepository userRepository, PersonRecordRepository personRecordRepository, FileRecordRepository fileRecordRepository, PersonSeeder personSeeder) {
        this.fileService = fileService;
        this.userRepository = userRepository;
        this.personRecordRepository = personRecordRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.personSeeder = personSeeder;
    }

    @Deprecated
    @PostMapping
    public ResponseEntity<FileRecordResponse> upload(
            Authentication authentication,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "policy", required = false) String policy,
            @RequestParam(value = "personNo", required = false) String personNo
    ) {
        AppPrincipal p = SecurityUtil.requirePrincipal(authentication);
        boolean isAdmin = p.getRole().name().equals("ADMIN");
        if (!isAdmin) {
            return ResponseEntity.status(403).build();
        }
        UserEntity uploader = userRepository.findById(p.getUserId()).orElseThrow();
        UserEntity target = new UserEntity();
        target.setId(uploader.getId());
        if (personNo != null && !personNo.isBlank()) {
            PersonRecordEntity pr = personRecordRepository.findByPersonNo(personNo.trim()).orElse(null);
            if (pr == null) {
                return ResponseEntity.badRequest().build();
            }
            target.setPersonId(pr.getId());
        } else {
            target.setPersonId(uploader.getPersonId());
        }
        FileRecordResponse resp = fileService.uploadAndEncrypt(target, file, policy);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/encrypted")
    public ResponseEntity<?> uploadEncrypted(
            Authentication authentication,
            @RequestBody EncryptedFileUploadRequest request
    ) {
        AppPrincipal p = SecurityUtil.requirePrincipal(authentication);
        boolean isAdmin = p.getRole().name().equals("ADMIN");
        if (!isAdmin) {
            return ResponseEntity.status(403).build();
        }
        
        UserEntity uploader = userRepository.findById(p.getUserId()).orElseThrow();
        String targetPersonNo = request == null ? null : request.getPersonNo();
        if ((targetPersonNo == null || targetPersonNo.isBlank()) && request != null && request.getPolicy() != null) {
            targetPersonNo = extractPersonNoFromPolicy(request.getPolicy());
        }

        UserEntity ownerToUse = uploader;
        if (targetPersonNo != null && !targetPersonNo.isBlank()) {
            String normalizedPersonNo = targetPersonNo.trim();
            PersonRecordEntity pr = personRecordRepository.findByPersonNo(normalizedPersonNo).orElse(null);
            if (pr == null) {
                pr = personSeeder.ensurePersonLoaded(normalizedPersonNo).orElse(null);
            }
            if (pr == null) {
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "profile_not_found"));
            }
            UserEntity targetUser = userRepository.findByPersonId(pr.getId()).orElse(null);
            if (targetUser == null) {
                targetUser = userRepository.findByAccount(normalizedPersonNo).orElse(null);
            }
            if (targetUser != null) {
                ownerToUse = targetUser;
            }
        }

        // #region debug-point B:upload-encrypted-request
        debugReport("B", "FilesController.uploadEncrypted", "[DEBUG] received encrypted upload request", Map.of(
                "adminUserId", safe(uploader.getId()),
                "adminAccount", safe(uploader.getAccount()),
                "targetPersonNo", safe(targetPersonNo),
                "resolvedOwnerId", safe(ownerToUse.getId()),
                "policy", request == null ? "" : safe(request.getPolicy()),
                "originalName", request == null ? "" : safe(request.getOriginalName())
        ));
        // #endregion

        FileRecordResponse resp = fileService.storeEncrypted(ownerToUse, request);
        return ResponseEntity.ok(resp);
    }

    @GetMapping
    public ResponseEntity<List<FileRecordResponse>> listMine(Authentication authentication) {
        AppPrincipal p = SecurityUtil.requirePrincipal(authentication);
        boolean isAdmin = p.getRole().name().equals("ADMIN");
        if (isAdmin) {
            return ResponseEntity.ok(fileService.listAll());
        }
        UserEntity user = userRepository.findById(p.getUserId()).orElseThrow();
        return ResponseEntity.ok(fileService.listAccessible(user));
    }

    @Deprecated
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(Authentication authentication, @PathVariable("id") String id) {
        AppPrincipal p = SecurityUtil.requirePrincipal(authentication);
        FileRecordEntity r = fileService.getRecordOrNull(id);
        if (r == null) {
            return ResponseEntity.status(404).build();
        }
        boolean isAdmin = p.getRole().name().equals("ADMIN");
        UserEntity user = userRepository.findById(p.getUserId()).orElseThrow();
        if (!isAdmin && !fileService.canUserAccess(r, user)) {
            return ResponseEntity.status(403).build();
        }
        byte[] raw = fileService.decryptForUser(r, user, AccessPurpose.USER_DOWNLOAD);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeFilename(r.getOriginalName()) + "\"")
                .contentType(MediaType.parseMediaType(r.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : r.getContentType()))
                .body(raw);
    }

    @GetMapping("/{id}/encrypted")
    public ResponseEntity<EncryptedFileResponse> downloadEncrypted(Authentication authentication, @PathVariable("id") String id) {
        AppPrincipal p = SecurityUtil.requirePrincipal(authentication);
        FileRecordEntity r = fileService.getRecordOrNull(id);
        if (r == null) {
            return ResponseEntity.status(404).build();
        }
        boolean isAdmin = p.getRole().name().equals("ADMIN");
        UserEntity user = userRepository.findById(p.getUserId()).orElseThrow();
        if (!isAdmin && !fileService.canUserAccess(r, user)) {
            return ResponseEntity.status(403).build();
        }
        EncryptedFileResponse resp = fileService.getEncryptedDataForUser(r, user, AccessPurpose.USER_DOWNLOAD);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/{id}/payload")
    public ResponseEntity<PlainFilePayloadResponse> payload(Authentication authentication, @PathVariable("id") String id) {
        AppPrincipal p = SecurityUtil.requirePrincipal(authentication);
        FileRecordEntity r = fileService.getRecordOrNull(id);
        if (r == null) {
            return ResponseEntity.status(404).build();
        }
        boolean isAdmin = p.getRole().name().equals("ADMIN");
        UserEntity user = userRepository.findById(p.getUserId()).orElseThrow();
        if (!isAdmin && !fileService.canUserAccess(r, user)) {
            return ResponseEntity.status(403).build();
        }
        byte[] raw = fileService.decryptForUser(r, user, AccessPurpose.USER_PAYLOAD);
        PlainFilePayloadResponse resp = new PlainFilePayloadResponse();
        resp.setDataBase64(java.util.Base64.getEncoder().encodeToString(raw));
        resp.setOriginalName(r.getOriginalName() == null ? null : r.getOriginalName().replace("\u0000", "").replace("\r", " ").replace("\n", " ").trim());
        resp.setContentType(r.getContentType());
        resp.setSizeBytes((long) raw.length);
        return ResponseEntity.ok(resp);
    }

    private static String safeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "download.bin";
        }
        return name.replace("\n", " ").replace("\r", " ");
    }

    private static String extractPersonNoFromPolicy(String policy) {
        String p = policy == null ? "" : policy.trim();
        if (p.isBlank()) {
            return null;
        }
        String[] parts = p.split("[,;| ]+");
        for (String part : parts) {
            if (part.startsWith("personNo:")) {
                String v = part.substring("personNo:".length()).trim();
                return v.isBlank() ? null : v;
            }
        }
        return null;
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats(Authentication authentication) {
        AppPrincipal p = SecurityUtil.requirePrincipal(authentication);
        boolean isAdmin = p.getRole().name().equals("ADMIN");
        
        long totalUploads;
        long availableData;
        
        if (isAdmin) {
            totalUploads = fileRecordRepository.count();
            availableData = totalUploads;
        } else {
            UserEntity user = userRepository.findById(p.getUserId()).orElseThrow();
            totalUploads = fileService.countOwnedBy(user);
            availableData = fileService.countAccessibleBy(user);
            // #region debug-point D:stats-user
            debugReport("D", "FilesController.stats", "[DEBUG] calculated user stats", Map.of(
                    "userId", safe(user.getId()),
                    "account", safe(user.getAccount()),
                    "personId", safe(user.getPersonId()),
                    "totalUploads", totalUploads,
                    "availableData", availableData
            ));
            // #endregion
        }
        
        return ResponseEntity.ok(Map.of(
            "totalUploads", totalUploads,
            "availableData", availableData
        ));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void debugReport(String hypothesisId, String location, String msg, Map<String, Object> data) {
        // Legacy debug forwarding is intentionally disabled.
    }
}
