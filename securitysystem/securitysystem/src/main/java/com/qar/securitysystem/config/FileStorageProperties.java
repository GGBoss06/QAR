package com.qar.securitysystem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.file")
public class FileStorageProperties {
    private long maxBytes = 25L * 1024L * 1024L;

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("app.file.max-bytes must be positive");
        }
        this.maxBytes = maxBytes;
    }
}
