package com.qar.securitysystem.service;

import com.qar.securitysystem.config.AdminProperties;
import com.qar.securitysystem.dto.LoginRequest;
import com.qar.securitysystem.dto.RegisterRequest;
import com.qar.securitysystem.dto.RegistrationResult;
import com.qar.securitysystem.model.AccountRequestEntity;
import com.qar.securitysystem.model.AccountRequestStatus;
import com.qar.securitysystem.model.PersonRecordEntity;
import com.qar.securitysystem.model.UserEntity;
import com.qar.securitysystem.model.UserRole;
import com.qar.securitysystem.repo.AccountRequestRepository;
import com.qar.securitysystem.repo.PersonRecordRepository;
import com.qar.securitysystem.repo.UserRepository;
import com.qar.securitysystem.util.IdUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminProperties adminProperties;
    private final PersonRecordRepository personRecordRepository;
    private final AccountRequestRepository accountRequestRepository;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, AdminProperties adminProperties, PersonRecordRepository personRecordRepository, AccountRequestRepository accountRequestRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminProperties = adminProperties;
        this.personRecordRepository = personRecordRepository;
        this.accountRequestRepository = accountRequestRepository;
    }

    public RegistrationResult submitAccountRequest(RegisterRequest req) {
        String personNo = normalize(req.getEmailOrUsername());
        if (personNo.isBlank()) {
            throw new IllegalArgumentException("emailOrUsername_required");
        }
        if (adminProperties.getUsername() != null && personNo.equalsIgnoreCase(adminProperties.getUsername())) {
            throw new IllegalArgumentException("admin_already_exists");
        }
        if (userRepository.existsByAccount(personNo)) {
            throw new IllegalArgumentException("user_already_exists");
        }

        String fullName = normalize(req.getFullName());
        if (fullName.isBlank()) {
            throw new IllegalArgumentException("fullName_required");
        }
        String idLast4 = normalize(req.getIdLast4());
        if (idLast4.isBlank()) {
            throw new IllegalArgumentException("idLast4_required");
        }
        String contact = normalize(req.getContact());
        if (contact.isBlank()) {
            throw new IllegalArgumentException("contact_required");
        }
        String airline = normalize(req.getAirline());
        if (airline.isBlank()) {
            throw new IllegalArgumentException("airline_required");
        }
        String positionTitle = normalize(req.getPositionTitle());
        if (positionTitle.isBlank()) {
            throw new IllegalArgumentException("position_required");
        }
        String department = normalize(req.getDepartment());
        if (department.isBlank()) {
            throw new IllegalArgumentException("department_required");
        }
        if (req.getPassword() == null || req.getPassword().isBlank()) {
            throw new IllegalArgumentException("password_required");
        }
        if (req.getPasswordConfirm() == null || !req.getPasswordConfirm().equals(req.getPassword())) {
            throw new IllegalArgumentException("password_confirm_mismatch");
        }

        AccountRequestEntity latest = accountRequestRepository.findFirstByPersonNoOrderByCreatedAtDesc(personNo).orElse(null);
        if (latest != null && latest.getStatus() == AccountRequestStatus.PENDING) {
            throw new IllegalArgumentException("request_already_pending");
        }

        PersonRecordEntity record = personRecordRepository.findByPersonNo(personNo).orElse(null);
        if (record == null) {
            throw new IllegalArgumentException("profile_not_found");
        }
        if (!fullName.equals(record.getFullName()) || !idLast4.equals(record.getIdLast4())) {
            throw new IllegalArgumentException("profile_mismatch");
        }
        if (record.getAirline() != null && !record.getAirline().isBlank() && !airline.equals(record.getAirline())) {
            throw new IllegalArgumentException("profile_mismatch");
        }
        if (record.getPositionTitle() != null && !record.getPositionTitle().isBlank() && !positionTitle.equals(record.getPositionTitle())) {
            throw new IllegalArgumentException("profile_mismatch");
        }
        if (record.getDepartment() != null && !record.getDepartment().isBlank() && !department.equals(record.getDepartment())) {
            throw new IllegalArgumentException("profile_mismatch");
        }
        if (record.getPhone() != null && !record.getPhone().isBlank() && !contact.equals(record.getPhone())) {
            throw new IllegalArgumentException("profile_mismatch");
        }

        AccountRequestEntity e = new AccountRequestEntity();
        e.setId(IdUtil.newId());
        e.setPersonNo(personNo);
        e.setFullName(fullName);
        e.setAirline(airline);
        e.setPositionTitle(positionTitle);
        e.setDepartment(department);
        e.setContact(contact);
        e.setIdLast4(idLast4);
        e.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        e.setStatus(AccountRequestStatus.PENDING);
        e.setCreatedAt(Instant.now());
        return new RegistrationResult(accountRequestRepository.save(e));
    }

    public UserEntity authenticate(LoginRequest req) {
        String username = normalize(req.getEmailOrUsername());
        if (username.isBlank() || req.getPassword() == null) {
            return null;
        }
        UserEntity u = userRepository.findByAccount(username).orElse(null);
        if (u == null) {
            return null;
        }
        if (!passwordEncoder.matches(req.getPassword(), u.getPasswordHash())) {
            return null;
        }
        return u;
    }

    public boolean isAccountRequestPending(String personNo) {
        if (personNo == null || personNo.isBlank()) {
            return false;
        }
        AccountRequestEntity latest = accountRequestRepository.findFirstByPersonNoOrderByCreatedAtDesc(personNo.trim()).orElse(null);
        return latest != null && latest.getStatus() == AccountRequestStatus.PENDING;
    }

    public UserEntity findUserById(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return userRepository.findById(userId).orElse(null);
    }

    private static String normalize(String v) {
        if (v == null) {
            return "";
        }
        return v.trim();
    }
}
