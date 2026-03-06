package com.bigbrightpaints.erp.modules.auth.domain;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByToken(String token);

    Optional<PasswordResetToken> findByTokenDigest(String tokenDigest);

    void deleteByToken(String token);

    @Modifying
    @Query("delete from PasswordResetToken t where t.tokenDigest = :tokenDigest")
    int deleteByTokenDigest(@Param("tokenDigest") String tokenDigest);

    void deleteByUser(UserAccount user);

    List<PasswordResetToken> findAllByTokenIsNotNullAndTokenDigestIsNull();
}
