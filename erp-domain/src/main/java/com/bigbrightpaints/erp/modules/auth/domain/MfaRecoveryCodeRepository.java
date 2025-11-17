package com.bigbrightpaints.erp.modules.auth.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for MFA recovery code operations.
 */
@Repository
public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, Long> {

    /**
     * Find all unused recovery codes for a user.
     */
    @Query("SELECT rc FROM MfaRecoveryCode rc WHERE rc.user = :user AND rc.usedAt IS NULL")
    List<MfaRecoveryCode> findUnusedByUser(@Param("user") UserAccount user);

    /**
     * Find a specific unused recovery code by user and hash.
     */
    @Query("SELECT rc FROM MfaRecoveryCode rc WHERE rc.user = :user AND rc.codeHash = :codeHash AND rc.usedAt IS NULL")
    Optional<MfaRecoveryCode> findUnusedByUserAndCodeHash(@Param("user") UserAccount user, @Param("codeHash") String codeHash);

    /**
     * Delete all recovery codes for a user (used when disabling MFA).
     */
    @Modifying
    @Query("DELETE FROM MfaRecoveryCode rc WHERE rc.user = :user")
    void deleteAllByUser(@Param("user") UserAccount user);

    /**
     * Count unused recovery codes for a user.
     */
    @Query("SELECT COUNT(rc) FROM MfaRecoveryCode rc WHERE rc.user = :user AND rc.usedAt IS NULL")
    long countUnusedByUser(@Param("user") UserAccount user);

    /**
     * Clean up old used recovery codes (for maintenance).
     */
    @Modifying
    @Query("DELETE FROM MfaRecoveryCode rc WHERE rc.usedAt IS NOT NULL AND rc.usedAt < :cutoffDate")
    int deleteUsedCodesBefore(@Param("cutoffDate") LocalDateTime cutoffDate);
}