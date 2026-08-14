package com.qar.securitysystem.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Issues short-lived HMAC-signed CSRF tokens without depending on a second
 * browser cookie or servlet session. The custom header requirement still
 * prevents cross-site forms from submitting a valid state-changing request.
 */
public final class StatelessCsrfTokenRepository implements CsrfTokenRepository {
    public static final String HEADER_NAME = "X-XSRF-TOKEN";
    private static final String PARAMETER_NAME = "_csrf";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecureRandom secureRandom = new SecureRandom();
    private final byte[] signingKey = new byte[32];
    private final Duration tokenTtl;

    public StatelessCsrfTokenRepository(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
        secureRandom.nextBytes(signingKey);
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        long expiresAt = Instant.now().plus(tokenTtl).getEpochSecond();
        byte[] nonce = new byte[24];
        secureRandom.nextBytes(nonce);
        String payload = expiresAt + "." + encode(nonce);
        return token(payload + "." + encode(sign(payload)));
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        // The signature and expiry are self-contained; no browser cookie or
        // server-side session state is required.
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        String value = request.getHeader(HEADER_NAME);
        if (value == null || value.isBlank()) {
            value = request.getParameter(PARAMETER_NAME);
        }
        return isValid(value) ? token(value) : null;
    }

    private boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 3) {
            return false;
        }
        try {
            long expiresAt = Long.parseLong(parts[0]);
            if (expiresAt < Instant.now().getEpochSecond()) {
                return false;
            }
            String payload = parts[0] + "." + parts[1];
            byte[] expected = sign(payload);
            byte[] supplied = Base64.getUrlDecoder().decode(parts[2]);
            return MessageDigest.isEqual(expected, supplied);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("csrf_signing_failed", e);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static CsrfToken token(String value) {
        return new DefaultCsrfToken(HEADER_NAME, PARAMETER_NAME, value);
    }
}
