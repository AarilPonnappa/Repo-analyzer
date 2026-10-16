package com.example.repolens.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.repolens.model.RepositoryEntity;

public interface RepositoryEntityRepository extends JpaRepository<RepositoryEntity, Long> {

    Optional<RepositoryEntity> findByOwnerRepo(String ownerRepo);
}
