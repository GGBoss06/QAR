package com.qar.securitysystem.repo;

import com.qar.securitysystem.model.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface UserRepository extends JpaRepository<UserEntity, String> {
    List<UserEntity> findAllByOrderByCreatedAtDesc();

    Optional<UserEntity> findByAccount(String account);
    Optional<UserEntity> findByPersonId(String personId);
    boolean existsByAccount(String account);
}
