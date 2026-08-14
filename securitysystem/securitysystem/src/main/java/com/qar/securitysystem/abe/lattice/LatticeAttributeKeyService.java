package com.qar.securitysystem.abe.lattice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class LatticeAttributeKeyService {
    public static final String KEY_SCHEME = "attribute-bound-kyber768-v2";

    private final LatticeCryptoSupport cryptoSupport;
    private final LatticeAuthorityKeyService authorityKeyService;
    private final ObjectMapper objectMapper;
    private final Path attributeKeyDir;

    public LatticeAttributeKeyService(LatticeCryptoSupport cryptoSupport,
                                      LatticeAuthorityKeyService authorityKeyService,
                                      ObjectMapper objectMapper,
                                      @Value("${app.crypto.lattice-attribute-dir:data/crypto/lattice-attributes}") String attributeKeyDir) {
        this.cryptoSupport = cryptoSupport;
        this.authorityKeyService = authorityKeyService;
        this.objectMapper = objectMapper;
        this.attributeKeyDir = Path.of(attributeKeyDir);
    }

    public synchronized AttributeKeyMaterial getAttributeMaterial(String attribute) {
        String normalized = normalizeAttribute(attribute);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("attribute_required");
        }
        String authorityId = authorityKeyService.resolveAuthorityForAttribute(normalized);
        try {
            Path authorityDir = attributeKeyDir.resolve(authorityId);
            Files.createDirectories(authorityDir);
            Path path = authorityDir.resolve(keyId(normalized) + ".json");
            if (Files.exists(path)) {
                AttributeKeyMaterial loaded = objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8), AttributeKeyMaterial.class);
                if (!normalized.equals(normalizeAttribute(loaded.attribute))) {
                    throw new IllegalStateException("attribute_key_identity_mismatch");
                }
                return loaded;
            }

            LatticeCryptoSupport.KyberKeyPair pair = cryptoSupport.generateKeyPair();
            AttributeKeyMaterial material = new AttributeKeyMaterial();
            material.keyId = keyId(normalized);
            material.attribute = normalized;
            material.authorityId = authorityId;
            material.algorithm = "kyber768";
            material.keyScheme = KEY_SCHEME;
            material.publicKey = Base64.getEncoder().encodeToString(pair.publicKey().getEncoded());
            material.privateKey = Base64.getEncoder().encodeToString(pair.privateKey().getEncoded());
            Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(material), StandardCharsets.UTF_8);
            return material;
        } catch (Exception e) {
            throw new RuntimeException("failed_to_load_lattice_attribute_key", e);
        }
    }

    public static String normalizeAttribute(String attribute) {
        return attribute == null ? "" : attribute.trim().toLowerCase(Locale.ROOT);
    }

    private static String keyId(String normalizedAttribute) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalizedAttribute.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("failed_to_hash_attribute", e);
        }
    }

    public static class AttributeKeyMaterial {
        public String keyId;
        public String attribute;
        public String authorityId;
        public String algorithm;
        public String keyScheme;
        public String publicKey;
        public String privateKey;
    }
}
