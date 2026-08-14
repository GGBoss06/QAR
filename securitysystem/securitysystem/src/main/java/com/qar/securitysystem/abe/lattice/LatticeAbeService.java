package com.qar.securitysystem.abe.lattice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qar.securitysystem.abe.AttributeAuthorityService;
import com.qar.securitysystem.model.UserEntity;
import com.qar.securitysystem.repo.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class LatticeAbeService {
    public static final String LATTICE_PREFIX = "LABE_LATTICE_BC:";

    private final LatticePolicyParser policyParser;
    private final LatticeCryptoSupport cryptoSupport;
    private final LatticeAuthorityKeyService authorityKeyService;
    private final LatticeAttributeKeyService attributeKeyService;
    private final LatticeUserSecretKeyService userSecretKeyService;
    private final UserRepository userRepository;
    private final AttributeAuthorityService attributeAuthorityService;
    private final ObjectMapper objectMapper;

    public LatticeAbeService(LatticePolicyParser policyParser, LatticeCryptoSupport cryptoSupport, LatticeAuthorityKeyService authorityKeyService, LatticeAttributeKeyService attributeKeyService, LatticeUserSecretKeyService userSecretKeyService, UserRepository userRepository, AttributeAuthorityService attributeAuthorityService, ObjectMapper objectMapper) {
        this.policyParser = policyParser;
        this.cryptoSupport = cryptoSupport;
        this.authorityKeyService = authorityKeyService;
        this.attributeKeyService = attributeKeyService;
        this.userSecretKeyService = userSecretKeyService;
        this.userRepository = userRepository;
        this.attributeAuthorityService = attributeAuthorityService;
        this.objectMapper = objectMapper;
    }

    public String wrap(byte[] fileKey, String policy) {
        try {
            LatticePolicyNode root = policyParser.parse(policy);
            if (root == null) {
                throw new IllegalArgumentException("empty_lattice_policy");
            }
            byte[] rootSecret = cryptoSupport.randomBytes(32);
            Envelope envelope = new Envelope();
            envelope.version = 3;
            envelope.scheme = "attribute-policy-user-bound-kyber768-v3";
            envelope.policy = policy == null ? "" : policy.trim();
            envelope.policyTree = root;
            envelope.rootDigest = b64(cryptoSupport.digest(rootSecret, aad(policy)));
            envelope.leaves = new ArrayList<>();
            share(root, rootSecret, envelope.leaves);
            envelope.recipients = buildRecipients(rootSecret, fileKey, policy);
            // #region debug-point C:wrap-envelope
            debugReport("C", "LatticeAbeService:wrap",
                    "[DEBUG] wrapped lattice file key",
                    Map.of(
                            "policy", envelope.policy,
                            "rootDigest", envelope.rootDigest,
                            "leafCount", envelope.leaves.size(),
                            "leafSummary", summarizeLeaves(envelope.leaves)
                    ));
            // #endregion
            return LATTICE_PREFIX + b64(objectMapper.writeValueAsBytes(envelope));
        } catch (Exception e) {
            // #region debug-point C:wrap-error
            debugReport("C", "LatticeAbeService:wrap:error",
                    "[DEBUG] failed to wrap lattice file key",
                    Map.of("policy", policy == null ? "" : policy.trim(), "error", e.toString()));
            // #endregion
            throw new RuntimeException("failed_to_wrap_lattice_key", e);
        }
    }

    public byte[] unwrapForUser(String wrappedKey, String policy, UserEntity user) {
        try {
            Envelope envelope = decode(wrappedKey);
            LatticeUserSecretKeyService.UserSecretBundle bundle = userSecretKeyService.getOrCreate(user);
            Set<String> attrs = bundle.attributes;
            byte[] rootSecret;
            if (envelope.version >= 3) {
                rootSecret = recoverSecret(envelope.policyTree, attrs, bundle.attributeKeys, indexLeaves(envelope.leaves));
            } else if (envelope.version >= 2) {
                rootSecret = recoverSecret(envelope.policyTree, attrs, bundle.attributeKeys, indexLeaves(envelope.leaves));
            } else {
                rootSecret = recoverLegacyForAuthorizedUser(
                        envelope.policyTree,
                        attrs,
                        indexLeaves(envelope.leaves),
                        authorityKeyService.getAllAuthorities()
                );
            }
            // #region debug-point D:unwrap-user
            debugReport("D", "LatticeAbeService:unwrapForUser",
                    "[DEBUG] unwrap lattice file key for user",
                    Map.of(
                            "userId", user == null ? null : user.getId(),
                            "policy", policy == null ? envelope.policy : policy,
                            "attributeCount", attrs == null ? 0 : attrs.size(),
                            "leafSummary", summarizeLeaves(envelope.leaves),
                            "rootRecovered", rootSecret != null
                    ));
            // #endregion
            if (rootSecret == null) {
                throw new AccessDeniedException("lattice_policy_not_satisfied");
            }
            byte[] expectedDigest = cryptoSupport.digest(rootSecret, aad(policy == null ? envelope.policy : policy));
            if (!constantTimeEquals(expectedDigest, b64d(envelope.rootDigest))) {
                throw new AccessDeniedException("lattice_root_secret_mismatch");
            }
            if (envelope.version >= 3) {
                return unwrapRecipientFileKey(envelope, rootSecret, bundle, policy == null ? envelope.policy : policy);
            }
            return cryptoSupport.decryptSecretWithAad(rootSecret, b64d(envelope.wrappedFileKey), aad(policy == null ? envelope.policy : policy));
        } catch (AccessDeniedException e) {
            // #region debug-point D:unwrap-user-denied
            debugReport("D", "LatticeAbeService:unwrapForUser:denied",
                    "[DEBUG] unwrap lattice file key denied",
                    Map.of("userId", user == null ? null : user.getId(), "error", e.toString(), "policy", policy));
            // #endregion
            throw e;
        } catch (Exception e) {
            // #region debug-point D:unwrap-user-error
            debugReport("D", "LatticeAbeService:unwrapForUser:error",
                    "[DEBUG] unwrap lattice file key failed for user",
                    Map.of("userId", user == null ? null : user.getId(), "error", e.toString(), "policy", policy));
            // #endregion
            throw new RuntimeException("failed_to_unwrap_lattice_key", e);
        }
    }

    public byte[] unwrapForSystem(String wrappedKey, String policy) {
        try {
            Envelope envelope = decode(wrappedKey);
            Map<Integer, LeafCiphertext> leafIndex = indexLeaves(envelope.leaves);
            Map<String, LatticeAuthorityKeyService.AuthorityKeyMaterial> authorities = envelope.version >= 2
                    ? Map.of()
                    : authorityKeyService.getAllAuthorities();
            byte[] rootSecret = envelope.version >= 2
                    ? recoverForSystemV2(envelope.policyTree, leafIndex)
                    : recoverForSystemLegacy(envelope.policyTree, leafIndex, authorities);
            // #region debug-point E:unwrap-system
            debugReport("E", "LatticeAbeService:unwrapForSystem",
                    "[DEBUG] unwrap lattice file key for system",
                    Map.of(
                            "policy", policy == null ? envelope.policy : policy,
                            "authorityFingerprints", summarizeAuthorities(authorities),
                            "leafSummary", summarizeLeaves(envelope.leaves),
                            "rootRecovered", rootSecret != null
                    ));
            // #endregion
            if (rootSecret == null) {
                throw new AccessDeniedException("lattice_policy_not_satisfied");
            }
            byte[] expectedDigest = cryptoSupport.digest(rootSecret, aad(policy == null ? envelope.policy : policy));
            if (!constantTimeEquals(expectedDigest, b64d(envelope.rootDigest))) {
                throw new AccessDeniedException("lattice_root_secret_mismatch");
            }
            if (envelope.version >= 3) {
                return unwrapSystemRecipientFileKey(envelope, rootSecret, policy == null ? envelope.policy : policy);
            }
            return cryptoSupport.decryptSecretWithAad(rootSecret, b64d(envelope.wrappedFileKey), aad(policy == null ? envelope.policy : policy));
        } catch (AccessDeniedException e) {
            // #region debug-point E:unwrap-system-denied
            debugReport("E", "LatticeAbeService:unwrapForSystem:denied",
                    "[DEBUG] unwrap lattice file key denied for system",
                    Map.of("policy", policy, "error", e.toString()));
            // #endregion
            throw e;
        } catch (Exception e) {
            // #region debug-point E:unwrap-system-error
            debugReport("E", "LatticeAbeService:unwrapForSystem:error",
                    "[DEBUG] unwrap lattice file key failed for system",
                    Map.of("policy", policy, "error", e.toString()));
            // #endregion
            throw new RuntimeException("failed_to_unwrap_lattice_key", e);
        }
    }

    public boolean isLatticeEnvelope(String wrappedKey) {
        return wrappedKey != null && wrappedKey.startsWith(LATTICE_PREFIX);
    }

    public boolean isLegacyEnvelope(String wrappedKey) {
        if (!isLatticeEnvelope(wrappedKey)) {
            return false;
        }
        try {
            return decode(wrappedKey).version < 3;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid_lattice_envelope", e);
        }
    }

    private void share(LatticePolicyNode node, byte[] secret, List<LeafCiphertext> leaves) {
        if (node == null) {
            return;
        }
        if (node.isLeaf()) {
            leaves.add(createLeaf(node, secret));
            return;
        }
        if (node.getType() == LatticePolicyNode.Type.OR) {
            for (LatticePolicyNode child : node.getChildren()) {
                share(child, secret, leaves);
            }
            return;
        }
        if (node.getChildren().isEmpty()) {
            return;
        }
        List<byte[]> childSecrets = new ArrayList<>();
        for (int i = 0; i < node.getChildren().size() - 1; i++) {
            childSecrets.add(cryptoSupport.randomBytes(secret.length));
        }
        byte[] tail = secret.clone();
        for (byte[] part : childSecrets) {
            tail = cryptoSupport.xor(tail, part);
        }
        childSecrets.add(tail);
        for (int i = 0; i < node.getChildren().size(); i++) {
            share(node.getChildren().get(i), childSecrets.get(i), leaves);
        }
    }

    private List<RecipientCiphertext> buildRecipients(byte[] rootSecret, byte[] fileKey, String policy) {
        List<RecipientCiphertext> recipients = new ArrayList<>();
        LatticeAttributeKeyService.AttributeKeyMaterial systemKey = attributeKeyService.getAttributeMaterial("system:recovery:v3");
        recipients.add(createRecipient(
                "__system__",
                systemKey.keyId,
                systemKey.publicKey,
                rootSecret,
                fileKey,
                policy
        ));
        for (UserEntity user : userRepository.findAllByOrderByCreatedAtDesc()) {
            if (!LatticeUserSecretKeyService.isUserAccessEnabled(user)
                    || !attributeAuthorityService.canUserAccess(policy, user)) {
                continue;
            }
            LatticeUserSecretKeyService.UserSecretBundle bundle = userSecretKeyService.getOrCreate(user);
            recipients.add(createRecipient(
                    user.getId(),
                    bundle.recipientKeyId,
                    bundle.recipientPublicKey,
                    rootSecret,
                    fileKey,
                    policy
            ));
        }
        return recipients;
    }

    private RecipientCiphertext createRecipient(String recipientId,
                                                 String keyId,
                                                 String publicKey,
                                                 byte[] rootSecret,
                                                 byte[] fileKey,
                                                 String policy) {
        LatticeCryptoSupport.KyberEncapsulationResult result = cryptoSupport.encapsulate(b64d(publicKey));
        byte[] wrappingSecret = recipientWrappingSecret(rootSecret, result.secret(), policy, recipientId, keyId);
        RecipientCiphertext recipient = new RecipientCiphertext();
        recipient.recipientId = recipientId;
        recipient.keyId = keyId;
        recipient.encapsulation = b64(result.encapsulation());
        recipient.wrappedFileKey = b64(cryptoSupport.encryptSecretWithAad(
                wrappingSecret,
                fileKey,
                recipientAad(policy, recipientId, keyId)
        ));
        return recipient;
    }

    private byte[] unwrapRecipientFileKey(Envelope envelope,
                                          byte[] rootSecret,
                                          LatticeUserSecretKeyService.UserSecretBundle bundle,
                                          String policy) {
        RecipientCiphertext recipient = findRecipient(envelope.recipients, bundle.userId, bundle.recipientKeyId);
        if (recipient == null) {
            throw new AccessDeniedException("lattice_recipient_not_authorized");
        }
        byte[] recipientSecret = cryptoSupport.decapsulate(b64d(bundle.recipientPrivateKey), b64d(recipient.encapsulation));
        byte[] wrappingSecret = recipientWrappingSecret(rootSecret, recipientSecret, policy, recipient.recipientId, recipient.keyId);
        return cryptoSupport.decryptSecretWithAad(
                wrappingSecret,
                b64d(recipient.wrappedFileKey),
                recipientAad(policy, recipient.recipientId, recipient.keyId)
        );
    }

    private byte[] unwrapSystemRecipientFileKey(Envelope envelope, byte[] rootSecret, String policy) {
        LatticeAttributeKeyService.AttributeKeyMaterial systemKey = attributeKeyService.getAttributeMaterial("system:recovery:v3");
        RecipientCiphertext recipient = findRecipient(envelope.recipients, "__system__", systemKey.keyId);
        if (recipient == null) {
            throw new AccessDeniedException("lattice_system_recipient_missing");
        }
        byte[] recipientSecret = cryptoSupport.decapsulate(b64d(systemKey.privateKey), b64d(recipient.encapsulation));
        byte[] wrappingSecret = recipientWrappingSecret(rootSecret, recipientSecret, policy, recipient.recipientId, recipient.keyId);
        return cryptoSupport.decryptSecretWithAad(
                wrappingSecret,
                b64d(recipient.wrappedFileKey),
                recipientAad(policy, recipient.recipientId, recipient.keyId)
        );
    }

    private byte[] recipientWrappingSecret(byte[] rootSecret,
                                           byte[] recipientSecret,
                                           String policy,
                                           String recipientId,
                                           String keyId) {
        return cryptoSupport.digest(
                rootSecret,
                crop(recipientSecret, 32),
                recipientAad(policy, recipientId, keyId)
        );
    }

    private static byte[] recipientAad(String policy, String recipientId, String keyId) {
        return ("LATTICE-RECIPIENT-V3:"
                + (policy == null ? "" : policy.trim())
                + ":" + (recipientId == null ? "" : recipientId)
                + ":" + (keyId == null ? "" : keyId)).getBytes(StandardCharsets.UTF_8);
    }

    private static RecipientCiphertext findRecipient(List<RecipientCiphertext> recipients, String recipientId, String keyId) {
        if (recipients == null) {
            return null;
        }
        return recipients.stream()
                .filter(recipient -> recipientId != null && recipientId.equals(recipient.recipientId))
                .filter(recipient -> keyId != null && keyId.equals(recipient.keyId))
                .findFirst()
                .orElse(null);
    }

    private LeafCiphertext createLeaf(LatticePolicyNode node, byte[] secret) {
        LatticeAttributeKeyService.AttributeKeyMaterial material = attributeKeyService.getAttributeMaterial(node.getAttribute());
        LatticeCryptoSupport.KyberEncapsulationResult result = cryptoSupport.encapsulate(b64d(material.publicKey));
        LeafCiphertext leaf = new LeafCiphertext();
        leaf.nodeId = node.getNodeId();
        leaf.attribute = node.getAttribute();
        leaf.authorityId = material.authorityId;
        leaf.keyId = material.keyId;
        leaf.attributeFingerprint = cryptoSupport.fingerprint(node.getAttribute().getBytes(StandardCharsets.UTF_8));
        leaf.encapsulation = b64(result.encapsulation());
        leaf.maskedSecret = b64(cryptoSupport.xor(secret, crop(result.secret(), secret.length)));
        // #region debug-point C:create-leaf
        debugReport("C", "LatticeAbeService:createLeaf",
                "[DEBUG] created lattice leaf",
                Map.of(
                        "nodeId", leaf.nodeId,
                        "attribute", leaf.attribute,
                        "authorityId", leaf.authorityId,
                        "attributeFingerprint", leaf.attributeFingerprint,
                        "authorityPublicKeyFingerprint", cryptoSupport.fingerprint(b64d(material.publicKey))
                ));
        // #endregion
        return leaf;
    }

    private byte[] recoverSecret(LatticePolicyNode node,
                                 Set<String> attrs,
                                 Map<String, LatticeUserSecretKeyService.AttributeSecretKey> keys,
                                 Map<Integer, LeafCiphertext> leaves) {
        if (node == null) {
            return null;
        }
        if (node.isLeaf()) {
            if (attrs == null || !attrs.contains(node.getAttribute())) {
                return null;
            }
            LatticeUserSecretKeyService.AttributeSecretKey key = keys == null ? null : keys.get(node.getAttribute());
            LeafCiphertext leaf = leaves.get(node.getNodeId());
            if (key == null || leaf == null) {
                // #region debug-point D:recover-missing
                debugReport("D", "LatticeAbeService:recoverSecret:missing",
                        "[DEBUG] missing leaf or key while recovering secret",
                        Map.of("attribute", node.getAttribute(), "nodeId", node.getNodeId(), "hasKey", key != null, "hasLeaf", leaf != null));
                // #endregion
                return null;
            }
            byte[] secret = cryptoSupport.decapsulate(b64d(key.privateKey), b64d(leaf.encapsulation));
            // #region debug-point D:recover-leaf
            debugReport("D", "LatticeAbeService:recoverSecret:leaf",
                    "[DEBUG] recovered user leaf secret",
                    Map.of(
                            "attribute", node.getAttribute(),
                            "nodeId", node.getNodeId(),
                            "authorityId", key.authorityId,
                            "bundlePublicKeyFingerprint", cryptoSupport.fingerprint(b64d(key.publicKey)),
                            "bundlePrivateKeyFingerprint", cryptoSupport.fingerprint(b64d(key.privateKey))
                    ));
            // #endregion
            return cryptoSupport.xor(crop(secret, 32), b64d(leaf.maskedSecret));
        }
        if (node.getType() == LatticePolicyNode.Type.OR) {
            for (LatticePolicyNode child : node.getChildren()) {
                byte[] recovered = recoverSecret(child, attrs, keys, leaves);
                if (recovered != null) {
                    return recovered;
                }
            }
            return null;
        }
        byte[] combined = new byte[32];
        boolean any = false;
        for (LatticePolicyNode child : node.getChildren()) {
            byte[] recovered = recoverSecret(child, attrs, keys, leaves);
            if (recovered == null) {
                return null;
            }
            combined = cryptoSupport.xor(combined, recovered);
            any = true;
        }
        return any ? combined : null;
    }

    private byte[] recoverForSystemLegacy(LatticePolicyNode node,
                                          Map<Integer, LeafCiphertext> leaves,
                                          Map<String, LatticeAuthorityKeyService.AuthorityKeyMaterial> authorities) {
        if (node == null) {
            return null;
        }
        if (node.isLeaf()) {
            LeafCiphertext leaf = leaves.get(node.getNodeId());
            if (leaf == null) {
                return null;
            }
            LatticeAuthorityKeyService.AuthorityKeyMaterial material = authorities.get(leaf.authorityId);
            if (material == null) {
                return null;
            }
            byte[] secret = cryptoSupport.decapsulate(b64d(material.privateKey), b64d(leaf.encapsulation));
            // #region debug-point E:recover-leaf-system
            debugReport("E", "LatticeAbeService:recoverForSystem:leaf",
                    "[DEBUG] recovered system leaf secret",
                    Map.of(
                            "attribute", leaf.attribute,
                            "nodeId", leaf.nodeId,
                            "authorityId", leaf.authorityId,
                            "authorityPublicKeyFingerprint", cryptoSupport.fingerprint(b64d(material.publicKey)),
                            "authorityPrivateKeyFingerprint", cryptoSupport.fingerprint(b64d(material.privateKey))
                    ));
            // #endregion
            return cryptoSupport.xor(crop(secret, 32), b64d(leaf.maskedSecret));
        }
        if (node.getType() == LatticePolicyNode.Type.OR) {
            for (LatticePolicyNode child : node.getChildren()) {
                byte[] recovered = recoverForSystemLegacy(child, leaves, authorities);
                if (recovered != null) {
                    return recovered;
                }
            }
            return null;
        }
        byte[] combined = new byte[32];
        boolean any = false;
        for (LatticePolicyNode child : node.getChildren()) {
            byte[] recovered = recoverForSystemLegacy(child, leaves, authorities);
            if (recovered == null) {
                return null;
            }
            combined = cryptoSupport.xor(combined, recovered);
            any = true;
        }
        return any ? combined : null;
    }

    private byte[] recoverLegacyForAuthorizedUser(
            LatticePolicyNode node,
            Set<String> attrs,
            Map<Integer, LeafCiphertext> leaves,
            Map<String, LatticeAuthorityKeyService.AuthorityKeyMaterial> authorities) {
        if (node == null) {
            return null;
        }
        if (node.isLeaf()) {
            if (attrs == null || !attrs.contains(node.getAttribute())) {
                return null;
            }
            LeafCiphertext leaf = leaves.get(node.getNodeId());
            LatticeAuthorityKeyService.AuthorityKeyMaterial material = leaf == null ? null : authorities.get(leaf.authorityId);
            if (material == null) {
                return null;
            }
            byte[] secret = cryptoSupport.decapsulate(b64d(material.privateKey), b64d(leaf.encapsulation));
            return cryptoSupport.xor(crop(secret, 32), b64d(leaf.maskedSecret));
        }
        if (node.getType() == LatticePolicyNode.Type.OR) {
            for (LatticePolicyNode child : node.getChildren()) {
                byte[] recovered = recoverLegacyForAuthorizedUser(child, attrs, leaves, authorities);
                if (recovered != null) {
                    return recovered;
                }
            }
            return null;
        }
        byte[] combined = new byte[32];
        boolean any = false;
        for (LatticePolicyNode child : node.getChildren()) {
            byte[] recovered = recoverLegacyForAuthorizedUser(child, attrs, leaves, authorities);
            if (recovered == null) {
                return null;
            }
            combined = cryptoSupport.xor(combined, recovered);
            any = true;
        }
        return any ? combined : null;
    }

    private byte[] recoverForSystemV2(LatticePolicyNode node, Map<Integer, LeafCiphertext> leaves) {
        if (node == null) {
            return null;
        }
        if (node.isLeaf()) {
            LeafCiphertext leaf = leaves.get(node.getNodeId());
            if (leaf == null) {
                return null;
            }
            LatticeAttributeKeyService.AttributeKeyMaterial material = attributeKeyService.getAttributeMaterial(leaf.attribute);
            if (leaf.keyId != null && !leaf.keyId.equals(material.keyId)) {
                throw new IllegalArgumentException("lattice_attribute_key_mismatch");
            }
            byte[] secret = cryptoSupport.decapsulate(b64d(material.privateKey), b64d(leaf.encapsulation));
            return cryptoSupport.xor(crop(secret, 32), b64d(leaf.maskedSecret));
        }
        if (node.getType() == LatticePolicyNode.Type.OR) {
            for (LatticePolicyNode child : node.getChildren()) {
                byte[] recovered = recoverForSystemV2(child, leaves);
                if (recovered != null) {
                    return recovered;
                }
            }
            return null;
        }
        byte[] combined = new byte[32];
        boolean any = false;
        for (LatticePolicyNode child : node.getChildren()) {
            byte[] recovered = recoverForSystemV2(child, leaves);
            if (recovered == null) {
                return null;
            }
            combined = cryptoSupport.xor(combined, recovered);
            any = true;
        }
        return any ? combined : null;
    }

    private static Map<Integer, LeafCiphertext> indexLeaves(List<LeafCiphertext> leaves) {
        LinkedHashMap<Integer, LeafCiphertext> map = new LinkedHashMap<>();
        if (leaves == null) {
            return map;
        }
        for (LeafCiphertext leaf : leaves) {
            map.put(leaf.nodeId, leaf);
        }
        return map;
    }

    private Envelope decode(String wrappedKey) throws Exception {
        String raw = wrappedKey.substring(LATTICE_PREFIX.length()).trim();
        return objectMapper.readValue(Base64.getDecoder().decode(raw), Envelope.class);
    }

    private static byte[] aad(String policy) {
        return ("LATTICE-LABE:" + (policy == null ? "" : policy.trim())).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] crop(byte[] input, int size) {
        byte[] out = new byte[size];
        System.arraycopy(input, 0, out, 0, Math.min(size, input.length));
        return out;
    }

    private static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private static byte[] b64d(String value) {
        return Base64.getDecoder().decode(value);
    }

    private static boolean constantTimeEquals(byte[] left, byte[] right) {
        if (left == null || right == null || left.length != right.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < left.length; i++) {
            diff |= left[i] ^ right[i];
        }
        return diff == 0;
    }

    public static class Envelope {
        public int version;
        public String scheme;
        public String policy;
        public String rootDigest;
        public String wrappedFileKey;
        public LatticePolicyNode policyTree;
        public List<LeafCiphertext> leaves;
        public List<RecipientCiphertext> recipients;
    }

    public static class LeafCiphertext {
        public int nodeId;
        public String attribute;
        public String attributeFingerprint;
        public String authorityId;
        public String keyId;
        public String encapsulation;
        public String maskedSecret;
    }

    public static class RecipientCiphertext {
        public String recipientId;
        public String keyId;
        public String encapsulation;
        public String wrappedFileKey;
    }

    private Map<String, Object> summarizeLeaves(List<LeafCiphertext> leaves) {
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        if (leaves == null) {
            return summary;
        }
        for (LeafCiphertext leaf : leaves) {
            summary.put(String.valueOf(leaf.nodeId), Map.of(
                    "attribute", leaf.attribute,
                    "authorityId", leaf.authorityId,
                    "attributeFingerprint", leaf.attributeFingerprint
            ));
        }
        return summary;
    }

    private Map<String, String> summarizeAuthorities(Map<String, LatticeAuthorityKeyService.AuthorityKeyMaterial> authorities) {
        LinkedHashMap<String, String> summary = new LinkedHashMap<>();
        if (authorities == null) {
            return summary;
        }
        for (Map.Entry<String, LatticeAuthorityKeyService.AuthorityKeyMaterial> entry : authorities.entrySet()) {
            LatticeAuthorityKeyService.AuthorityKeyMaterial material = entry.getValue();
            summary.put(entry.getKey(),
                    cryptoSupport.fingerprint(b64d(material.publicKey)) + ":" + cryptoSupport.fingerprint(b64d(material.privateKey)));
        }
        return summary;
    }

    private static void debugReport(String hypothesisId, String location, String msg, Map<String, Object> data) {
        // Legacy debug forwarding is intentionally disabled.
    }
}
