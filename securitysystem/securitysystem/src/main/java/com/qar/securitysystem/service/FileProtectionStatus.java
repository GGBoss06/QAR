package com.qar.securitysystem.service;

import com.qar.securitysystem.abe.FileKeyEnvelopeService;

public final class FileProtectionStatus {
    private static final String LEGACY_PLAIN = "PLAIN_TEXT";

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
        return "ENCRYPTED";
    }
}
