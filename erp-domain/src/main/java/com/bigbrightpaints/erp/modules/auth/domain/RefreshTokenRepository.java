package com.bigbrightpaints.erp.modules.auth.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenDigest(String tokenDigest);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenDigest = :tokenDigest")
    Optional<RefreshToken> findForUpdateByTokenDigest(@Param("tokenDigest") String tokenDigest);

    @Modifying
    @Query("delete from RefreshToken t where lower(t.userEmail) = lower(:userEmail)")
    int deleteByUserEmail(@Param("userEmail") String userEmail);

    @Modifying
    @Query("delete from RefreshToken t where t.tokenDigest = :tokenDigest")
    int deleteByTokenDigest(@Param("tokenDigest") String tokenDigest);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredTokens(@Param("cutoff") Instant cutoff);
}
