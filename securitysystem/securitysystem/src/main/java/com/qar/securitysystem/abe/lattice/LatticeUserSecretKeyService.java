package com.qar.securitysystem.abe.lattice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qar.securitysystem.abe.AttributeAuthorityService;
import com.qar.securitysystem.model.UserEntity;
import com.qar.securitysystem.util.IdUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class LatticeUserSecretKeyService {
    private final AttributeAuthorityService attributeAuthorityService;
    private final LatticeAttributeKeyService attributeKeyService;
    private final LatticeCryptoSupport cryptoSupport;
    private final ObjectMapper objectMapper;
    private final Path userKeyDir;
    private final Path userKeyHistoryDir;

    public LatticeUserSecretKeyService(AttributeAuthorityService attributeAuthorityService,
                                       LatticeAttributeKeyService attributeKeyService,
                                       LatticeCryptoSupport cryptoSupport,
                                       ObjectMapper objectMapper,
                                       @Value("${app.crypto.lattice-user-dir:data/crypto/lattice-users}") String userKeyDir) {
        this.attributeAuthorityService = attributeAuthorityService;
        this.attributeKeyService = attributeKeyService;
        this.cryptoSupport = cryptoSupport;
        this.objectMapper = objectMapper;
        this.userKeyDir = Path.of(userKeyDir);
        this.userKeyHistoryDir = this.userKeyDir.resolve("history");
    }

    public UserSecretBundle getOrCreate(UserEntity user) {
        if (user == null || user.getId() == null || user.getId().isBlank()) {
            throw new IllegalArgumentException("invalid_user_for_lattice_key");
        }
        try {
            Set<String> attributes = attributeAuthorityService.resolveUserAttributes(user);
            UserSecretBundle existing = loadIfExists(user.getId());
            if (existing != null
                    && LatticeAttributeKeyService.KEY_SCHEME.equals(existing.keyScheme)
                    && existing.recipientPrivateKey != null && !existing.recipientPrivateKey.isBlank()
                    && existing.recipientPublicKey != null && !existing.recipientPublicKey.isBlank()
                    && attributes.equals(existing.attributes)) {
                // #region debug-point B:user-bundle-reuse
                debugReport("B", "LatticeUserSecretKeyService:getOrCreate:reuse",
                        "[DEBUG] reused lattice user secret bundle",
                        Map.of(
                                "userId", user.getId(),
                                "attributeCount", attributes.size(),
                                "attributes", attributes,
                                "bundleVersion", existing.bundleVersion == null ? 0L : existing.bundleVersion,
                                "bundleKeyFingerprints", summarizeBundle(existing)
                        ));
                // #endregion
                return existing;
            }
            return issueForUser(user, existing == null ? "lazy_initial_issue" : "lazy_attribute_rotation");
        } catch (Exception e) {
            // #region debug-point B:user-bundle-error
            debugReport("B", "LatticeUserSecretKeyService:getOrCreate:error",
                    "[DEBUG] failed to build lattice user secret bundle",
                    Map.of("userId", user == null ? null : user.getId(), "error", e.toString()));
            // #endregion
            throw new RuntimeException("failed_to_build_lattice_user_secret", e);
        }
    }

    public UserSecretBundle issueForUser(UserEntity user, String reason) {
        if (user == null || user.getId() == null || user.getId().isBlank()) {
            throw new IllegalArgumentException("invalid_user_for_lattice_key");
        }
        if (!isUserAccessEnabled(user)) {
            throw new IllegalArgumentException("user_access_frozen");
        }
        try {
            Files.createDirectories(userKeyDir);
            Files.createDirectories(userKeyHistoryDir);
            Path path = bundlePath(user.getId());
            UserSecretBundle previous = loadIfExists(user.getId());
            if (previous != null) {
                archiveBundle(previous, reason);
            }
            Set<String> attributes = attributeAuthorityService.resolveUserAttributes(user);
            UserSecretBundle bundle = buildBundle(user, attributes, previous, normalizeReason(reason));
            writeJsonAtomically(path, bundle);
            // #region debug-point B:user-bundle-build
            debugReport("B", "LatticeUserSecretKeyService:issueForUser:build",
                    "[DEBUG] issued lattice user secret bundle",
                    Map.of(
                            "userId", user.getId(),
                            "reason", bundle.issuedReason,
                            "bundleVersion", bundle.bundleVersion == null ? 0L : bundle.bundleVersion,
                            "attributeCount", attributes.size(),
                            "attributes", attributes,
                            "attributeDigest", bundle.attributeDigest == null ? "" : bundle.attributeDigest,
                            "bundleKeyFingerprints", summarizeBundle(bundle)
                    ));
            // #endregion
            return bundle;
        } catch (Exception e) {
            debugReport("B", "LatticeUserSecretKeyService:issueForUser:error",
                    "[DEBUG] failed to issue lattice user secret bundle",
                    Map.of(
                            "userId", user.getId(),
                            "reason", normalizeReason(reason),
                            "error", e.toString()
                    ));
            throw new RuntimeException("failed_to_build_lattice_user_secret", e);
        }
    }

    public void revokeActiveBundle(String userId, String reason) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("invalid_user_for_lattice_key");
        }
        try {
            Files.createDirectories(userKeyDir);
            Files.createDirectories(userKeyHistoryDir);
            UserSecretBundle active = loadIfExists(userId);
            if (active == null) {
                return;
            }
            active.status = "revoked";
            active.revokedAt = Instant.now();
            active.revokedReason = normalizeReason(reason);
            long version = active.bundleVersion == null ? 0L : active.bundleVersion;
            Path historyPath = userKeyHistoryDir.resolve(userId + ".v" + version + ".json");
            writeArchivedAudit(historyPath, active);
            Files.deleteIfExists(bundlePath(userId));
        } catch (Exception e) {
            throw new RuntimeException("failed_to_revoke_lattice_user_secret", e);
        }
    }

    public UserSecretBundle loadIfExists(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            Path path = bundlePath(userId);
            if (!Files.exists(path)) {
                return null;
            }
            UserSecretBundle bundle = objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8), UserSecretBundle.class);
            if (bundle != null && (bundle.status == null || bundle.status.isBlank())) {
                bundle.status = "active";
            }
            return bundle;
        } catch (Exception e) {
            throw new RuntimeException("failed_to_load_lattice_user_secret", e);
        }
    }

    public boolean hasBundle(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        return Files.exists(bundlePath(userId));
    }

    public long countExistingBundles() {
        try {
            if (!Files.exists(userKeyDir)) {
                return 0L;
            }
            try (var stream = Files.list(userKeyDir)) {
                return stream.filter(path -> path.getFileName().toString().endsWith(".json")).count();
            }
        } catch (Exception e) {
            return 0L;
        }
    }

    public long sanitizeArchivedBundles() {
        try {
            if (!Files.exists(userKeyHistoryDir)) {
                return 0L;
            }
            long sanitized = 0L;
            try (var stream = Files.list(userKeyHistoryDir)) {
                for (Path path : stream.filter(item -> item.getFileName().toString().endsWith(".json")).toList()) {
                    JsonNode root = objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
                    if (scrubPrivateKeys(root)) {
                        writeJsonAtomically(path, root);
                        sanitized++;
                    }
                }
            }
            return sanitized;
        } catch (Exception e) {
            throw new RuntimeException("failed_to_sanitize_lattice_key_history", e);
        }
    }

    public Map<String, AttributeSecretKey> getAttributeKeys(String userId) {
        UserSecretBundle bundle = loadIfExists(userId);
        if (bundle == null || bundle.attributeKeys == null) {
            return Collections.emptyMap();
        }
        return bundle.attributeKeys;
    }

    public static class UserSecretBundle {
        public String bundleId;
        public String userId;
        public Long bundleVersion;
        public String status;
        public String issuedReason;
        public String revokedReason;
        public String attributeDigest;
        public String keyScheme;
        public String recipientKeyId;
        public String recipientPublicKey;
        public String recipientPrivateKey;
        public Instant issuedAt;
        public Instant revokedAt;
        public Set<String> attributes;
        public Map<String, AttributeSecretKey> attributeKeys;
    }

    public static class AttributeSecretKey {
        public String keyId;
        public String attribute;
        public String authorityId;
        public String algorithm;
        public String publicKey;
        public String privateKey;
    }

    private static Map<String, String> summarizeBundle(UserSecretBundle bundle) {
        LinkedHashMap<String, String> summary = new LinkedHashMap<>();
        if (bundle == null || bundle.attributeKeys == null) {
            return summary;
        }
        for (Map.Entry<String, AttributeSecretKey> entry : bundle.attributeKeys.entrySet()) {
            AttributeSecretKey secret = entry.getValue();
            summary.put(entry.getKey(),
                    (secret == null ? "null" : (secret.authorityId + ":" + shortFingerprint(secret.publicKey) + ":" + shortFingerprint(secret.privateKey))));
        }
        return summary;
    }

    private UserSecretBundle buildBundle(UserEntity user, Set<String> attributes, UserSecretBundle previous, String reason) {
        UserSecretBundle bundle = new UserSecretBundle();
        bundle.bundleId = IdUtil.newId();
        bundle.userId = user.getId();
        bundle.bundleVersion = nextBundleVersion(user.getId(), previous);
        bundle.status = "active";
        bundle.issuedReason = reason;
        bundle.issuedAt = Instant.now();
        bundle.attributes = attributes;
        bundle.attributeDigest = digestAttributes(attributes);
        bundle.keyScheme = LatticeAttributeKeyService.KEY_SCHEME;
        LatticeCryptoSupport.KyberKeyPair recipientPair = cryptoSupport.generateKeyPair();
        bundle.recipientPublicKey = Base64.getEncoder().encodeToString(recipientPair.publicKey().getEncoded());
        bundle.recipientPrivateKey = Base64.getEncoder().encodeToString(recipientPair.privateKey().getEncoded());
        bundle.recipientKeyId = cryptoSupport.fingerprint(recipientPair.publicKey().getEncoded());
        bundle.attributeKeys = new LinkedHashMap<>();
        for (String attribute : attributes) {
            LatticeAttributeKeyService.AttributeKeyMaterial material = attributeKeyService.getAttributeMaterial(attribute);
            AttributeSecretKey secret = new AttributeSecretKey();
            secret.keyId = material.keyId;
            secret.attribute = attribute;
            secret.authorityId = material.authorityId;
            secret.algorithm = material.algorithm;
            secret.privateKey = material.privateKey;
            secret.publicKey = material.publicKey;
            bundle.attributeKeys.put(attribute, secret);
        }
        return bundle;
    }

    private long nextBundleVersion(String userId, UserSecretBundle previous) {
        long current = previous == null || previous.bundleVersion == null ? 0L : previous.bundleVersion;
        return Math.max(current, latestArchivedVersion(userId)) + 1L;
    }

    private long latestArchivedVersion(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0L;
        }
        try {
            if (!Files.exists(userKeyHistoryDir)) {
                return 0L;
            }
            String prefix = userId + ".v";
            try (var stream = Files.list(userKeyHistoryDir)) {
                return stream
                        .map(path -> extractArchivedVersion(path.getFileName().toString(), prefix))
                        .filter(version -> version >= 0)
                        .max(Long::compareTo)
                        .orElse(0L);
            }
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long extractArchivedVersion(String fileName, String prefix) {
        if (fileName == null || !fileName.startsWith(prefix) || !fileName.endsWith(".json")) {
            return -1L;
        }
        int start = prefix.length();
        int end = fileName.length() - ".json".length();
        if (start >= end) {
            return -1L;
        }
        try {
            return Long.parseLong(fileName.substring(start, end));
        } catch (Exception e) {
            return -1L;
        }
    }

    private void archiveBundle(UserSecretBundle bundle, String reason) {
        try {
            if (bundle == null || bundle.userId == null || bundle.userId.isBlank()) {
                return;
            }
            if (!LatticeAttributeKeyService.KEY_SCHEME.equals(bundle.keyScheme)) {
                return;
            }
            bundle.status = "rotated";
            bundle.revokedAt = Instant.now();
            bundle.revokedReason = normalizeReason(reason);
            long version = bundle.bundleVersion == null ? 0L : bundle.bundleVersion;
            String archiveName = bundle.userId + ".v" + version + ".json";
            Path historyPath = userKeyHistoryDir.resolve(archiveName);
            writeArchivedAudit(historyPath, bundle);
        } catch (Exception e) {
            throw new RuntimeException("failed_to_archive_lattice_user_secret", e);
        }
    }

    private void writeArchivedAudit(Path historyPath, UserSecretBundle source) throws Exception {
        JsonNode audit = objectMapper.valueToTree(source);
        scrubPrivateKeys(audit);
        writeJsonAtomically(historyPath, audit);
    }

    private static boolean scrubPrivateKeys(JsonNode node) {
        boolean changed = false;
        if (node instanceof ObjectNode objectNode) {
            for (var fields = objectNode.fields(); fields.hasNext(); ) {
                var field = fields.next();
                String normalizedName = field.getKey().replace("_", "").replace("-", "").toLowerCase();
                if ((normalizedName.contains("privatekey") || normalizedName.equals("secretkey"))
                        && !field.getValue().isNull()) {
                    objectNode.putNull(field.getKey());
                    changed = true;
                } else {
                    changed |= scrubPrivateKeys(field.getValue());
                }
            }
        } else if (node != null && node.isArray()) {
            for (JsonNode child : node) {
                changed |= scrubPrivateKeys(child);
            }
        }
        return changed;
    }

    private void writeJsonAtomically(Path destination, Object value) throws Exception {
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp-" + IdUtil.newId());
        try {
            Files.writeString(temporary,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path bundlePath(String userId) {
        return userKeyDir.resolve(userId + ".json");
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "manual_issue";
        }
        return reason.trim();
    }

    public static boolean isUserAccessEnabled(UserEntity user) {
        return user == null || user.getAccessEnabled() == null || user.getAccessEnabled();
    }

    private static String digestAttributes(Set<String> attributes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> sorted = attributes == null ? List.of() : attributes.stream().sorted(String::compareToIgnoreCase).toList();
            for (String attribute : sorted) {
                digest.update(attribute.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            byte[] raw = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(raw.length, 12); i++) {
                sb.append(String.format("%02x", raw[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "digest-error";
        }
    }

    private static String shortFingerprint(String value) {
        try {
            byte[] raw = value == null ? new byte[0] : Base64.getDecoder().decode(value);
            int size = Math.min(8, raw.length);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < size; i++) {
                sb.append(String.format("%02x", raw[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "decode-error";
        }
    }

    private static void debugReport(String hypothesisId, String location, String msg, Map<String, Object> data) {
        // Legacy debug forwarding is intentionally disabled.
    }
}
