package com.qar.securitysystem.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "users")
public class UserEntity {
    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "email_or_username", unique = true, nullable = false)
    private String account;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "person_id", length = 64)
    private String personId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "access_enabled")
    private Boolean accessEnabled;

    @Column(name = "access_revoked_at")
    private Instant accessRevokedAt;

    @Column(name = "access_revoked_reason", length = 240)
    private String accessRevokedReason;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getPersonId() {
        return personId;
    }

    public void setPersonId(String personId) {
        this.personId = personId;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Boolean getAccessEnabled() {
        return accessEnabled;
    }

    public void setAccessEnabled(Boolean accessEnabled) {
        this.accessEnabled = accessEnabled;
    }

    public Instant getAccessRevokedAt() {
        return accessRevokedAt;
    }

    public void setAccessRevokedAt(Instant accessRevokedAt) {
        this.accessRevokedAt = accessRevokedAt;
    }

    public String getAccessRevokedReason() {
        return accessRevokedReason;
    }

    public void setAccessRevokedReason(String accessRevokedReason) {
        this.accessRevokedReason = accessRevokedReason;
    }
}
