package com.qar.securitysystem.repo;

import com.qar.securitysystem.model.PersonRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface PersonRecordRepository extends JpaRepository<PersonRecordEntity, String> {
    List<PersonRecordEntity> findAllByOrderByPersonNoAsc();

    Optional<PersonRecordEntity> findByPersonNo(String personNo);
    boolean existsByPersonNo(String personNo);
}

