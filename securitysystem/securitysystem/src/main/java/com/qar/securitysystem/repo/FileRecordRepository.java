package com.qar.securitysystem.repo;

import com.qar.securitysystem.model.FileRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FileRecordRepository extends JpaRepository<FileRecordEntity, String> {
    List<FileRecordEntity> findAllByOrderByCreatedAtDesc();

    List<FileRecordEntity> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);

    List<FileRecordEntity> findAllByOwnerIdInOrderByCreatedAtDesc(List<String> ownerIds);

    long countByOwnerId(String ownerId);

    long countByOwnerIdIn(List<String> ownerIds);
}
