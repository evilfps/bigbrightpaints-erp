package com.bigbrightpaints.erp.modules.accounting.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BankReconciliationSessionRepository extends JpaRepository<BankReconciliationSession, Long> {

    Optional<BankReconciliationSession> findByCompanyAndId(Company company, Long id);

    @Query("""
            select s
            from BankReconciliationSession s
            where s.company = :company
            order by s.createdAt desc, s.id desc
            """)
    Page<BankReconciliationSession> findHistoryByCompany(@Param("company") Company company, Pageable pageable);

    @Query("""
            select s
            from BankReconciliationSession s
            join fetch s.bankAccount
            left join fetch s.accountingPeriod
            where s.company = :company
              and s.id = :id
            """)
    Optional<BankReconciliationSession> findDetailedByCompanyAndId(@Param("company") Company company,
                                                                    @Param("id") Long id);
}
