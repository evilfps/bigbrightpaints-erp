package com.bigbrightpaints.erp.modules.accounting.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findByCompanyOrderByCodeAsc(Company company);
    Optional<Account> findByCompanyAndId(Company company, Long id);
    Optional<Account> findByCompanyAndCodeIgnoreCase(Company company, String code);
    List<Account> findByCompanyAndIdIn(Company company, List<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.company = :company and a.id = :id")
    Optional<Account> lockByCompanyAndId(@Param("company") Company company, @Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.company = :company and a.id in :ids order by a.id")
    List<Account> lockByCompanyAndIdIn(@Param("company") Company company, @Param("ids") List<Long> ids);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Account a SET a.balance = a.balance + :delta WHERE a.company = :company AND a.id = :id")
    int updateBalanceAtomic(@Param("company") Company company, @Param("id") Long id, @Param("delta") BigDecimal delta);

    // Hierarchy queries
    List<Account> findByCompanyAndParentIsNullOrderByCodeAsc(Company company); // Root accounts
    
    List<Account> findByCompanyAndParentOrderByCodeAsc(Company company, Account parent); // Children of parent
    
    @Query("SELECT a FROM Account a WHERE a.company = :company AND a.parent.id = :parentId ORDER BY a.code")
    List<Account> findChildrenByParentId(@Param("company") Company company, @Param("parentId") Long parentId);
    
    @Query("SELECT a FROM Account a WHERE a.company = :company ORDER BY a.hierarchyLevel, a.code")
    List<Account> findAllOrderedByHierarchy(@Param("company") Company company);
}
