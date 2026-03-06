package com.bigbrightpaints.erp.modules.auth.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);

    Optional<RefreshToken> findByTokenDigest(String tokenDigest);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.token = :token")
    Optional<RefreshToken> findForUpdate(@Param("token") String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenDigest = :tokenDigest")
    Optional<RefreshToken> findForUpdateByTokenDigest(@Param("tokenDigest") String tokenDigest);

    @Modifying
    @Query("delete from RefreshToken t where lower(t.userEmail) = lower(:userEmail)")
    int deleteByUserEmail(@Param("userEmail") String userEmail);

    @Modifying
    @Query("delete from RefreshToken t where t.token = :token")
    int deleteByToken(@Param("token") String token);

    @Modifying
    @Query("delete from RefreshToken t where t.tokenDigest = :tokenDigest")
    int deleteByTokenDigest(@Param("tokenDigest") String tokenDigest);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredTokens(@Param("cutoff") Instant cutoff);

    List<RefreshToken> findAllByTokenIsNotNullAndTokenDigestIsNull();
}
