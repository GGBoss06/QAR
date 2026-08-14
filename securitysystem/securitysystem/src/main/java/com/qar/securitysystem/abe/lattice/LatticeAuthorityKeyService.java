package com.qar.securitysystem.abe.lattice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class LatticeAuthorityKeyService {
    private static final String[] AUTHORITIES = {"person", "org", "ops", "security", "system"};

    private final LatticeCryptoSupport cryptoSupport;
    private final ObjectMapper objectMapper;
    private final Path authorityDir = Path.of("data", "crypto", "lattice-authorities");

    public LatticeAuthorityKeyService(LatticeCryptoSupport cryptoSupport, ObjectMapper objectMapper) {
        this.cryptoSupport = cryptoSupport;
        this.objectMapper = objectMapper;
    }

    public AuthorityKeyMaterial getAuthorityMaterial(String authorityId) {
        String resolved = normalizeAuthority(authorityId);
        try {
            Files.createDirectories(authorityDir);
            Path path = authorityDir.resolve(resolved + ".json");
            if (Files.exists(path)) {
                AuthorityKeyMaterial loaded = objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8), AuthorityKeyMaterial.class);
                // #region debug-point A:authority-load-existing
                debugReport("A", "LatticeAuthorityKeyService:getAuthorityMaterial:existing",
                        "[DEBUG] loaded lattice authority material",
                        Map.of(
                                "authorityId", resolved,
                                "path", path.toString(),
                                "publicKeyFingerprint", cryptoSupport.fingerprint(safeDecode(loaded.publicKey)),
                                "privateKeyFingerprint", cryptoSupport.fingerprint(safeDecode(loaded.privateKey))
                        ));
                // #endregion
                return loaded;
            }
            LatticeCryptoSupport.KyberKeyPair pair = cryptoSupport.generateKeyPair();
            AuthorityKeyMaterial material = new AuthorityKeyMaterial();
            material.authorityId = resolved;
            material.algorithm = "kyber768";
            material.publicKey = Base64.getEncoder().encodeToString(pair.publicKey().getEncoded());
            material.privateKey = Base64.getEncoder().encodeToString(pair.privateKey().getEncoded());
            Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(material), StandardCharsets.UTF_8);
            // #region debug-point A:authority-generate
            debugReport("A", "LatticeAuthorityKeyService:getAuthorityMaterial:generated",
                    "[DEBUG] generated lattice authority material",
                    Map.of(
                            "authorityId", resolved,
                            "path", path.toString(),
                            "publicKeyFingerprint", cryptoSupport.fingerprint(pair.publicKey().getEncoded()),
                            "privateKeyFingerprint", cryptoSupport.fingerprint(pair.privateKey().getEncoded())
                    ));
            // #endregion
            return material;
        } catch (Exception e) {
            // #region debug-point A:authority-error
            debugReport("A", "LatticeAuthorityKeyService:getAuthorityMaterial:error",
                    "[DEBUG] failed to load lattice authority material",
                    Map.of("authorityId", resolved, "error", e.toString()));
            // #endregion
            throw new RuntimeException("failed_to_load_lattice_authority", e);
        }
    }

    public Map<String, AuthorityKeyMaterial> getAllAuthorities() {
        LinkedHashMap<String, AuthorityKeyMaterial> map = new LinkedHashMap<>();
        for (String authority : AUTHORITIES) {
            map.put(authority, getAuthorityMaterial(authority));
        }
        return map;
    }

    public String resolveAuthorityForAttribute(String attribute) {
        String normalized = attribute == null ? "" : attribute.trim().toLowerCase();
        if (normalized.startsWith("personno:") || normalized.startsWith("personid:") || normalized.startsWith("fullname:") || normalized.startsWith("account:") || normalized.startsWith("userid:")) {
            return "person";
        }
        if (normalized.startsWith("department:") || normalized.startsWith("airline:") || normalized.startsWith("fleetgroup:")) {
            return "org";
        }
        if (normalized.startsWith("position:") || normalized.startsWith("positiontitle:") || normalized.startsWith("personcategory:") || normalized.startsWith("dutydomain:")) {
            return "ops";
        }
        if (normalized.startsWith("clearancelevel:")) {
            return "security";
        }
        return "system";
    }

    private static String normalizeAuthority(String authorityId) {
        if (authorityId == null || authorityId.isBlank()) {
            return "system";
        }
        return authorityId.trim().toLowerCase();
    }

    public static class AuthorityKeyMaterial {
        public String authorityId;
        public String algorithm;
        public String publicKey;
        public String privateKey;
    }

    private static byte[] safeDecode(String value) {
        try {
            return value == null ? new byte[0] : Base64.getDecoder().decode(value);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static void debugReport(String hypothesisId, String location, String msg, Map<String, Object> data) {
        // Legacy debug forwarding is intentionally disabled.
    }
}
