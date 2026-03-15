package com.bigbrightpaints.erp.modules.auth.domain;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenDigest(String tokenDigest);

    @Modifying
    @Query("delete from PasswordResetToken t where t.tokenDigest = :tokenDigest")
    int deleteByTokenDigest(@Param("tokenDigest") String tokenDigest);

    void deleteByUser(UserAccount user);

    @Modifying
    @Query("delete from PasswordResetToken t where t.user = :user and t.id <> :keepId")
    int deleteByUserAndIdNot(@Param("user") UserAccount user, @Param("keepId") Long keepId);

    Optional<PasswordResetToken> findTopByUserOrderByCreatedAtDescIdDesc(UserAccount user);

    @Modifying
    @Query("update PasswordResetToken t set t.createdAt = :createdAt where t.id = :tokenId")
    int touchCreatedAt(@Param("tokenId") Long tokenId, @Param("createdAt") java.time.Instant createdAt);
}
