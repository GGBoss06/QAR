package com.qar.securitysystem.startup;

import com.qar.securitysystem.config.PersonSeedProperties;
import com.qar.securitysystem.model.PersonRecordEntity;
import com.qar.securitysystem.repo.PersonRecordRepository;
import com.qar.securitysystem.util.IdUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class PersonSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PersonSeeder.class);
    private final PersonRecordRepository personRecordRepository;
    private final PersonSeedProperties props;
    private final ResourceLoader resourceLoader;

    public PersonSeeder(PersonRecordRepository personRecordRepository, PersonSeedProperties props, ResourceLoader resourceLoader) {
        this.personRecordRepository = personRecordRepository;
        this.props = props;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!props.isSeedEnabled()) {
            return;
        }
        seedMissingPersons();
    }

    public Optional<PersonRecordEntity> ensurePersonLoaded(String personNo) {
        String normalized = personNo == null ? "" : personNo.trim();
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        Optional<PersonRecordEntity> existing = personRecordRepository.findByPersonNo(normalized);
        if (existing.isPresent() || !props.isSeedEnabled()) {
            return existing;
        }
        return seedSinglePerson(normalized);
    }

    private void seedMissingPersons() throws Exception {
        Resource r = resourceLoader.getResource(props.getSeedCsv());
        if (!r.exists()) {
            return;
        }
        String rawContent = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        List<String> lines = rawContent.lines().toList();
        if (lines.isEmpty()) {
            return;
        }
        boolean first = true;
        for (String line : lines) {
            if (first) {
                first = false;
                continue;
            }
            String v = line.trim();
            if (v.isBlank()) {
                continue;
            }
            String[] parts = v.split(",", -1);
            if (parts.length < 11) {
                log.warn("Skipping person seed row with {} columns; expected 11", parts.length);
                continue;
            }
            String personNo = parts[0].trim();
            String fullName = parts[1].trim();
            String idLast4 = parts[2].trim();
            String phone = parts[3].trim();
            String dept = parts[4].trim();
            String airline = parts[5].trim();
            String positionTitle = parts[6].trim();
            String personCategory = parts[7].trim();
            String dutyDomain = parts[8].trim();
            String fleetGroup = parts[9].trim();
            String clearanceLevel = parts[10].trim();
            if (personNo.isBlank() || fullName.isBlank() || idLast4.isBlank()) {
                continue;
            }
            if (personRecordRepository.existsByPersonNo(personNo)) {
                continue;
            }
            savePerson(personNo, fullName, idLast4, phone, dept, airline, positionTitle,
                    personCategory, dutyDomain, fleetGroup, clearanceLevel);
        }
    }

    private Optional<PersonRecordEntity> seedSinglePerson(String targetPersonNo) {
        try {
            Resource r = resourceLoader.getResource(props.getSeedCsv());
            if (!r.exists()) {
                return Optional.empty();
            }
            String rawContent = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean first = true;
            for (String line : rawContent.lines().toList()) {
                if (first) {
                    first = false;
                    continue;
                }
                String v = line.trim();
                if (v.isBlank()) {
                    continue;
                }
                String[] parts = v.split(",", -1);
                if (parts.length < 11) {
                    continue;
                }
                String personNo = parts[0].trim();
                if (!targetPersonNo.equals(personNo)) {
                    continue;
                }
                String fullName = parts[1].trim();
                String idLast4 = parts[2].trim();
                String phone = parts[3].trim();
                String dept = parts[4].trim();
                String airline = parts[5].trim();
                String positionTitle = parts[6].trim();
                String personCategory = parts[7].trim();
                String dutyDomain = parts[8].trim();
                String fleetGroup = parts[9].trim();
                String clearanceLevel = parts[10].trim();
                return Optional.of(savePerson(personNo, fullName, idLast4, phone, dept, airline, positionTitle,
                        personCategory, dutyDomain, fleetGroup, clearanceLevel));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to seed person on demand for personNo={}", targetPersonNo, e);
            return Optional.empty();
        }
    }

    private PersonRecordEntity savePerson(String personNo, String fullName, String idLast4, String phone,
                                          String dept, String airline, String positionTitle,
                                          String personCategory, String dutyDomain,
                                          String fleetGroup, String clearanceLevel) {
        PersonRecordEntity e = new PersonRecordEntity();
        e.setId(IdUtil.newId());
        e.setPersonNo(personNo);
        e.setFullName(fullName);
        e.setIdLast4(idLast4);
        e.setPhone(phone == null || phone.isBlank() ? null : phone);
        e.setDepartment(dept == null || dept.isBlank() ? null : dept);
        e.setAirline(airline == null || airline.isBlank() ? null : airline);
        e.setPositionTitle(positionTitle == null || positionTitle.isBlank() ? null : positionTitle);
        e.setPersonCategory(emptyToNull(personCategory));
        e.setDutyDomain(emptyToNull(dutyDomain));
        e.setFleetGroup(emptyToNull(fleetGroup));
        e.setClearanceLevel(emptyToNull(clearanceLevel));
        e.setCreatedAt(Instant.now());
        return personRecordRepository.save(e);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
