package com.bigbrightpaints.erp.modules.accounting.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long>, JpaSpecificationExecutor<JournalEntry> {
    @EntityGraph(attributePaths = {"lines", "lines.account"})
    List<JournalEntry> findByCompanyOrderByEntryDateDesc(Company company);
    @EntityGraph(attributePaths = {"lines", "lines.account"})
    List<JournalEntry> findByCompanyAndDealerOrderByEntryDateDesc(Company company, Dealer dealer);
    @EntityGraph(attributePaths = {"lines", "lines.account"})
    Page<JournalEntry> findByCompanyOrderByEntryDateDesc(Company company, Pageable pageable);
    Page<JournalEntry> findByCompanyAndDealerOrderByEntryDateDesc(Company company, Dealer dealer, Pageable pageable);
    @EntityGraph(attributePaths = {"lines", "lines.account"})
    Page<JournalEntry> findByCompanyOrderByEntryDateDescIdDesc(Company company, Pageable pageable);
    @EntityGraph(attributePaths = {"lines", "lines.account"})
    Page<JournalEntry> findByCompanyAndDealerOrderByEntryDateDescIdDesc(Company company, Dealer dealer, Pageable pageable);
    @EntityGraph(attributePaths = {"lines", "lines.account"})
    Page<JournalEntry> findByCompanyAndSupplierOrderByEntryDateDescIdDesc(Company company, Supplier supplier, Pageable pageable);
    @EntityGraph(attributePaths = {"lines", "lines.account"})
    Optional<JournalEntry> findById(Long id);
    @EntityGraph(attributePaths = {"lines", "lines.account"})
    Optional<JournalEntry> findByCompanyAndId(Company company, Long id);
    Optional<JournalEntry> findByCompanyAndReferenceNumber(Company company, String referenceNumber);
    Optional<JournalEntry> findFirstByCompanyAndReferenceNumberStartingWith(Company company, String referencePrefix);
    @EntityGraph(attributePaths = {"lines"})
    List<JournalEntry> findByCompanyAndReversalOfAndCorrectionReasonIgnoreCase(Company company,
                                                                              JournalEntry reversalOf,
                                                                              String correctionReason);
    
    // Find all related entries for cascade reversal (e.g., INV-001 finds INV-001-COGS, INV-001-TAX)
    List<JournalEntry> findByCompanyAndReferenceNumberStartingWith(Company company, String referencePrefix);
    
    long countByCompanyAndEntryDateBetweenAndStatusIn(Company company, LocalDate start, LocalDate end, Collection<String> statuses);
    @EntityGraph(attributePaths = {"lines", "lines.account"})
    List<JournalEntry> findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(Company company, LocalDate start, LocalDate end);
    @EntityGraph(attributePaths = {"lines", "lines.account"})
    List<JournalEntry> findByCompanyAndEntryDateAfterOrderByEntryDateAsc(Company company, LocalDate end);

    @EntityGraph(attributePaths = {"lines"})
    List<JournalEntry> findAll();

    @Query("select je.company.id from JournalEntry je where je.id = :id")
    Optional<Long> findCompanyIdById(@Param("id") Long id);
}
