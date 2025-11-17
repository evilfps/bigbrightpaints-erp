package com.bigbrightpaints.erp.modules.accounting.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findByCompanyOrderByCodeAsc(Company company);
    Optional<Account> findByCompanyAndId(Company company, Long id);
    Optional<Account> findByCompanyAndCodeIgnoreCase(Company company, String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.company = :company and a.id = :id")
    Optional<Account> lockByCompanyAndId(@Param("company") Company company, @Param("id") Long id);
}
