package com.qar.securitysystem.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "account_requests")
public class AccountRequestEntity {
    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(name = "person_no", length = 64, nullable = false)
    private String personNo;

    @Column(name = "full_name", length = 80, nullable = false)
    private String fullName;

    @Column(name = "airline", length = 120, nullable = false)
    private String airline;

    @Column(name = "position_title", length = 120, nullable = false)
    private String positionTitle;

    @Column(name = "department", length = 120, nullable = false)
    private String department;

    @Column(name = "contact", length = 80, nullable = false)
    private String contact;

    @Column(name = "id_last4", length = 8, nullable = false)
    private String idLast4;

    @Column(name = "password_hash", length = 255, nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private AccountRequestStatus status;

    @Column(name = "admin_note", length = 400)
    private String adminNote;

    @Column(name = "reviewed_by_admin_id", length = 64)
    private String reviewedByAdminId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPersonNo() {
        return personNo;
    }

    public void setPersonNo(String personNo) {
        this.personNo = personNo;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getAirline() {
        return airline;
    }

    public void setAirline(String airline) {
        this.airline = airline;
    }

    public String getPositionTitle() {
        return positionTitle;
    }

    public void setPositionTitle(String positionTitle) {
        this.positionTitle = positionTitle;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    public String getIdLast4() {
        return idLast4;
    }

    public void setIdLast4(String idLast4) {
        this.idLast4 = idLast4;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public AccountRequestStatus getStatus() {
        return status;
    }

    public void setStatus(AccountRequestStatus status) {
        this.status = status;
    }

    public String getAdminNote() {
        return adminNote;
    }

    public void setAdminNote(String adminNote) {
        this.adminNote = adminNote;
    }

    public String getReviewedByAdminId() {
        return reviewedByAdminId;
    }

    public void setReviewedByAdminId(String reviewedByAdminId) {
        this.reviewedByAdminId = reviewedByAdminId;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

}

