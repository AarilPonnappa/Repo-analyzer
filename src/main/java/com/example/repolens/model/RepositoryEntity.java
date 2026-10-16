package com.example.repolens.model;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// One row per GitHub repo, e.g. "spring-projects/spring-petclinic".
// Named RepositoryEntity so it doesn't clash with Spring's @Repository.
@Entity
@Table(name = "repository")
public class RepositoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String ownerRepo;
    private boolean isPrivate;
    private String defaultBranch;
    private Instant createdAt = Instant.now();

    protected RepositoryEntity() {}   // JPA needs a no-args constructor

    public RepositoryEntity(String ownerRepo, boolean isPrivate, String defaultBranch) {
        this.ownerRepo = ownerRepo;
        this.isPrivate = isPrivate;
        this.defaultBranch = defaultBranch;
    }

    public Long getId() { return id; }
    public String getOwnerRepo() { return ownerRepo; }
    public boolean isPrivate() { return isPrivate; }
    public void setPrivate(boolean isPrivate) { this.isPrivate = isPrivate; }
    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }
    public Instant getCreatedAt() { return createdAt; }
}
