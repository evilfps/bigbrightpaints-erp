package com.bigbrightpaints.erp.modules.auth.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
  Optional<UserAccount> findByPublicId(UUID publicId);

  Optional<UserAccount> findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
      String email, String authScopeCode);

  boolean existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(String email, String authScopeCode);

  boolean existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCaseAndIdNot(
      String email, String authScopeCode, Long id);

  List<UserAccount> findAllByAuthScopeCodeIgnoreCase(String authScopeCode);

  List<UserAccount> findByPublicIdIn(Iterable<UUID> publicIds);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from UserAccount u where u.id = :id")
  Optional<UserAccount> lockById(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select u from UserAccount u
      where lower(u.email) = lower(:email)
        and upper(u.authScopeCode) = upper(:authScopeCode)
      """)
  Optional<UserAccount> lockByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
      @Param("email") String email, @Param("authScopeCode") String authScopeCode);

  List<UserAccount> findByCompany_Id(Long companyId);

  long countByCompany_Id(Long companyId);

  long countByCompany_IdAndEnabledTrue(Long companyId);

  long countByCompany_IdAndMfaEnabledTrue(Long companyId);

  Optional<UserAccount> findByIdAndCompany_Id(Long id, Long companyId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from UserAccount u where u.id = :id and u.company.id = :companyId")
  Optional<UserAccount> lockByIdAndCompanyId(
      @Param("id") Long id, @Param("companyId") Long companyId);
}
