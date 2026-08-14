package com.qar.securitysystem.abe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qar.securitysystem.abe.lattice.LatticeAbeService;
import com.qar.securitysystem.model.FileRecordEntity;
import com.qar.securitysystem.model.UserEntity;
import com.qar.securitysystem.util.AesGcmUtil;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;

@Service
public class FileKeyEnvelopeService {
    public static final String LABE_PREFIX = "LABE_PROTO_BC:";
    public static final String LATTICE_PREFIX = LatticeAbeService.LATTICE_PREFIX;

    private final AttributeAuthorityService attributeAuthorityService;
    private final LatticeAbeService latticeAbeService;
    private final ObjectMapper objectMapper;
    private final Path masterKeyPath = Path.of("data", "crypto", "labe-master.key");

    private volatile SecretKey masterKey;

    public FileKeyEnvelopeService(AttributeAuthorityService attributeAuthorityService, LatticeAbeService latticeAbeService, ObjectMapper objectMapper) {
        this.attributeAuthorityService = attributeAuthorityService;
        this.latticeAbeService = latticeAbeService;
        this.objectMapper = objectMapper;
    }

    public String wrapForStorage(byte[] keyBytes, String policy) {
        if (policy != null && !policy.isBlank()) {
            return latticeAbeService.wrap(keyBytes, policy);
        }
        try {
            byte[] iv = AesGcmUtil.newIv();
            byte[] ciphertext = AesGcmUtil.encrypt(loadOrCreateMasterKey(), iv, keyBytes, buildAad(policy));

            Envelope envelope = new Envelope();
            envelope.algorithm = "labe-prototype-aesgcm-v1";
            envelope.policy = policy == null ? "" : policy.trim();
            envelope.iv = Base64.getEncoder().encodeToString(iv);
            envelope.ciphertext = Base64.getEncoder().encodeToString(ciphertext);
            envelope.createdAt = Instant.now().toString();
            return LABE_PREFIX + Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(envelope));
        } catch (Exception e) {
            throw new RuntimeException("failed_to_wrap_storage_key", e);
        }
    }

    public byte[] unwrapForUser(FileRecordEntity record, UserEntity user, AccessPurpose purpose) {
        String wrappedKey = record == null ? null : record.getWrappedKey();
        if (!isLabeEnvelope(wrappedKey)) {
            throw new IllegalStateException("unsupported_file_key_envelope");
        }
        String policy = record == null ? null : record.getPolicy();
        if (!attributeAuthorityService.canUserAccess(policy, user)) {
            throw new AccessDeniedException("labe_policy_not_satisfied");
        }
        if (isLatticeEnvelope(wrappedKey)) {
            return latticeAbeService.unwrapForUser(wrappedKey, policy, user);
        }
        return unwrapEnvelope(wrappedKey, policy);
    }

    public byte[] unwrapForSystem(FileRecordEntity record, AccessPurpose purpose) {
        String wrappedKey = record == null ? null : record.getWrappedKey();
        if (!isLabeEnvelope(wrappedKey)) {
            throw new IllegalStateException("unsupported_file_key_envelope");
        }
        String policy = record == null ? null : record.getPolicy();
        if (!attributeAuthorityService.canSystemAccess(policy, purpose)) {
            throw new AccessDeniedException("labe_policy_not_satisfied");
        }
        if (isLatticeEnvelope(wrappedKey)) {
            return latticeAbeService.unwrapForSystem(wrappedKey, policy);
        }
        return unwrapEnvelope(wrappedKey, policy);
    }

    public boolean isLabeEnvelope(String wrappedKey) {
        return wrappedKey != null && (wrappedKey.startsWith(LABE_PREFIX) || wrappedKey.startsWith(LATTICE_PREFIX));
    }

    public boolean isLatticeEnvelope(String wrappedKey) {
        return latticeAbeService.isLatticeEnvelope(wrappedKey);
    }

    private byte[] unwrapEnvelope(String wrappedKey, String policy) {
        try {
            String raw = wrappedKey.substring(LABE_PREFIX.length()).trim();
            Envelope envelope = objectMapper.readValue(Base64.getDecoder().decode(raw), Envelope.class);
            byte[] iv = Base64.getDecoder().decode(envelope.iv);
            byte[] ciphertext = Base64.getDecoder().decode(envelope.ciphertext);
            return AesGcmUtil.decrypt(loadOrCreateMasterKey(), iv, ciphertext, buildAad(policy == null ? envelope.policy : policy));
        } catch (Exception e) {
            throw new RuntimeException("failed_to_unwrap_storage_key", e);
        }
    }

    private SecretKey loadOrCreateMasterKey() {
        SecretKey local = masterKey;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (masterKey == null) {
                masterKey = loadOrGenerateMasterKey();
            }
            return masterKey;
        }
    }

    private SecretKey loadOrGenerateMasterKey() {
        try {
            Files.createDirectories(masterKeyPath.getParent());
            if (Files.exists(masterKeyPath)) {
                String base64 = Files.readString(masterKeyPath, StandardCharsets.UTF_8).trim();
                return new SecretKeySpec(Base64.getDecoder().decode(base64), "AES");
            }
            SecretKey generated = AesGcmUtil.generateKey();
            Files.writeString(masterKeyPath, Base64.getEncoder().encodeToString(generated.getEncoded()), StandardCharsets.UTF_8);
            return generated;
        } catch (Exception e) {
            throw new RuntimeException("failed_to_initialize_labe_master_key", e);
        }
    }

    private static byte[] buildAad(String policy) {
        String safe = policy == null ? "" : policy.trim();
        return ("L-ABE:" + safe).getBytes(StandardCharsets.UTF_8);
    }

    private static class Envelope {
        public String algorithm;
        public String policy;
        public String iv;
        public String ciphertext;
        public String createdAt;
    }
}
