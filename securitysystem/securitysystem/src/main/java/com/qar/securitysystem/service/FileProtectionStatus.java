package com.qar.securitysystem.service;

import com.qar.securitysystem.abe.FileKeyEnvelopeService;

public final class FileProtectionStatus {
    private static final String LEGACY_PLAIN = "PLAIN_TEXT";
    private static final String LEGACY_RSA_PREFIX = "RSA_WRAP_BC:";

    private FileProtectionStatus() {
    }

    public static String fromWrappedKey(String wrappedKey) {
        if (wrappedKey == null || wrappedKey.isBlank() || LEGACY_PLAIN.equals(wrappedKey)) {
            return "UNPROTECTED";
        }
        if (wrappedKey.startsWith(FileKeyEnvelopeService.LATTICE_PREFIX)) {
            return "ATTRIBUTE_CONTROLLED";
        }
        if (wrappedKey.startsWith(FileKeyEnvelopeService.LABE_PREFIX)) {
            return "LEGACY_ATTRIBUTE";
        }
        if (wrappedKey.startsWith(LEGACY_RSA_PREFIX)) {
            return "LEGACY_RSA";
        }
        return "ENCRYPTED";
    }
}
