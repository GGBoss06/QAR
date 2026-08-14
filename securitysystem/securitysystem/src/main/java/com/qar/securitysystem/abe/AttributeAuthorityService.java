package com.qar.securitysystem.abe;

import com.qar.securitysystem.model.PersonRecordEntity;
import com.qar.securitysystem.model.UserEntity;
import com.qar.securitysystem.model.UserRole;
import com.qar.securitysystem.repo.PersonRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AttributeAuthorityService {
    private final PersonRecordRepository personRecordRepository;
    private final AttributePolicyEvaluator policyEvaluator;

    public AttributeAuthorityService(PersonRecordRepository personRecordRepository, AttributePolicyEvaluator policyEvaluator) {
        this.personRecordRepository = personRecordRepository;
        this.policyEvaluator = policyEvaluator;
    }

    public Set<String> resolveUserAttributes(UserEntity user) {
        LinkedHashSet<String> attrs = new LinkedHashSet<>();
        if (user == null) {
            return attrs;
        }
        append(attrs, "account", user.getAccount());
        append(attrs, "userId", user.getId());
        if (user.getRole() != null) {
            append(attrs, "role", user.getRole().name().toLowerCase(Locale.ROOT));
        }

        PersonRecordEntity record = resolvePersonRecord(user);
        attrs.addAll(resolvePersonAttributes(record));
        return attrs;
    }

    public Set<String> resolvePersonAttributes(PersonRecordEntity record) {
        LinkedHashSet<String> attrs = new LinkedHashSet<>();
        if (record == null) {
            return attrs;
        }
        append(attrs, "personId", record.getId());
        append(attrs, "personNo", record.getPersonNo());
        append(attrs, "department", record.getDepartment());
        append(attrs, "airline", record.getAirline());
        append(attrs, "position", record.getPositionTitle());
        append(attrs, "positionTitle", record.getPositionTitle());
        append(attrs, "fullName", record.getFullName());
        append(attrs, "personCategory", record.getPersonCategory());
        append(attrs, "dutyDomain", record.getDutyDomain());
        append(attrs, "fleetGroup", record.getFleetGroup());
        append(attrs, "clearanceLevel", record.getClearanceLevel());
        return attrs;
    }

    public boolean canUserAccess(String policy, UserEntity user) {
        if (user != null && user.getRole() == UserRole.ADMIN) {
            return true;
        }
        Set<String> attrs = resolveUserAttributes(user);
        boolean allowed = policyEvaluator.evaluate(policy, attrs);
        // #region debug-point C:user-attributes
        debugReport("C", "AttributeAuthorityService.canUserAccess", "[DEBUG] resolved user attributes", Map.of(
                "account", user == null ? "" : safe(user.getAccount()),
                "userId", user == null ? "" : safe(user.getId()),
                "personId", user == null ? "" : safe(user.getPersonId()),
                "policy", safe(policy),
                "allowed", allowed,
                "attributes", attrs
        ));
        // #endregion
        return allowed;
    }

    public boolean canSystemAccess(String policy, AccessPurpose purpose) {
        LinkedHashSet<String> attrs = new LinkedHashSet<>();
        append(attrs, "role", "admin");
        append(attrs, "system", "true");
        append(attrs, "purpose", purpose == null ? AccessPurpose.SYSTEM_INTERNAL.name().toLowerCase(Locale.ROOT) : purpose.name().toLowerCase(Locale.ROOT));
        return policyEvaluator.evaluate(policy, attrs) || purpose == AccessPurpose.ADMIN_EXPORT || purpose == AccessPurpose.ADMIN_PREVIEW || purpose == AccessPurpose.SYSTEM_INTERNAL;
    }

    private PersonRecordEntity resolvePersonRecord(UserEntity user) {
        if (user == null) {
            return null;
        }
        if (user.getPersonId() != null && !user.getPersonId().isBlank()) {
            return personRecordRepository.findById(user.getPersonId()).orElse(null);
        }
        if (user.getAccount() != null && !user.getAccount().isBlank()) {
            return personRecordRepository.findByPersonNo(user.getAccount().trim()).orElse(null);
        }
        return null;
    }

    private static void append(Set<String> attrs, String key, String value) {
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        attrs.add((key.trim() + ":" + value.trim()).toLowerCase(Locale.ROOT));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void debugReport(String hypothesisId, String location, String msg, Map<String, Object> data) {
        // Legacy debug forwarding is intentionally disabled.
    }
}
