package com.example.repolens.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.repolens.model.AnalysisRun;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, Long> {

    // Loads the run together with its repository in one query
    @Query("select r from AnalysisRun r join fetch r.repository where r.id = :id")
    Optional<AnalysisRun> findWithRepository(@Param("id") Long id);

    // Cache check: was this exact commit already analyzed?
    Optional<AnalysisRun> findFirstByRepository_OwnerRepoAndBranchAndCommitShaAndStatusOrderByFinishedAtDesc(
            String ownerRepo, String branch, String commitSha, AnalysisRun.Status status);

    // "Recently analyzed": finished runs of public repos only, newest first
    @Query("select r from AnalysisRun r join fetch r.repository repo "
            + "where repo.isPrivate = false and r.status = :status order by r.finishedAt desc")
    List<AnalysisRun> findRecentPublic(@Param("status") AnalysisRun.Status status, Pageable pageable);
}
