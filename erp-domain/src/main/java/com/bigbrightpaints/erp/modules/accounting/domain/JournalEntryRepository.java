package com.bigbrightpaints.erp.modules.accounting.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;

public interface JournalEntryRepository
    extends JpaRepository<JournalEntry, Long>, JpaSpecificationExecutor<JournalEntry> {
  @EntityGraph(attributePaths = {"lines", "lines.account"})
  List<JournalEntry> findByCompanyOrderByEntryDateDesc(Company company);

  @EntityGraph(attributePaths = {"lines", "lines.account"})
  List<JournalEntry> findByCompanyAndDealerOrderByEntryDateDesc(Company company, Dealer dealer);

  @EntityGraph(attributePaths = {"lines", "lines.account"})
  Page<JournalEntry> findByCompanyOrderByEntryDateDesc(Company company, Pageable pageable);

  Page<JournalEntry> findByCompanyAndDealerOrderByEntryDateDesc(
      Company company, Dealer dealer, Pageable pageable);

  @EntityGraph(
      attributePaths = {
        "lines",
        "lines.account",
        "dealer",
        "supplier",
        "accountingPeriod",
        "reversalOf",
        "reversalEntry"
      })
  Page<JournalEntry> findByCompanyOrderByEntryDateDescIdDesc(Company company, Pageable pageable);

  @EntityGraph(
      attributePaths = {
        "lines",
        "lines.account",
        "dealer",
        "supplier",
        "accountingPeriod",
        "reversalOf",
        "reversalEntry"
      })
  Page<JournalEntry> findByCompanyAndDealerOrderByEntryDateDescIdDesc(
      Company company, Dealer dealer, Pageable pageable);

  @EntityGraph(
      attributePaths = {
        "lines",
        "lines.account",
        "dealer",
        "supplier",
        "accountingPeriod",
        "reversalOf",
        "reversalEntry"
      })
  Page<JournalEntry> findByCompanyAndDealerAndSourceModuleIgnoreCaseOrderByEntryDateDescIdDesc(
      Company company, Dealer dealer, String sourceModule, Pageable pageable);

  @EntityGraph(
      attributePaths = {
        "lines",
        "lines.account",
        "dealer",
        "supplier",
        "accountingPeriod",
        "reversalOf",
        "reversalEntry"
      })
  Page<JournalEntry> findByCompanyAndSupplierOrderByEntryDateDescIdDesc(
      Company company, Supplier supplier, Pageable pageable);

  @EntityGraph(
      attributePaths = {
        "lines",
        "lines.account",
        "dealer",
        "supplier",
        "accountingPeriod",
        "reversalOf",
        "reversalEntry"
      })
  Page<JournalEntry> findByCompanyAndSupplierAndSourceModuleIgnoreCaseOrderByEntryDateDescIdDesc(
      Company company, Supplier supplier, String sourceModule, Pageable pageable);

  @EntityGraph(
      attributePaths = {
        "lines",
        "lines.account",
        "dealer",
        "supplier",
        "accountingPeriod",
        "reversalOf",
        "reversalEntry"
      })
  Page<JournalEntry> findByCompanyAndSourceModuleIgnoreCaseOrderByEntryDateDescIdDesc(
      Company company, String sourceModule, Pageable pageable);

  @EntityGraph(attributePaths = {"lines", "lines.account"})
  List<JournalEntry> findByCompanyAndSourceModuleIgnoreCaseOrderByEntryDateDescIdDesc(
      Company company, String sourceModule);

  @EntityGraph(attributePaths = {"lines", "lines.account"})
  Optional<JournalEntry> findById(Long id);

  @EntityGraph(attributePaths = {"lines", "lines.account"})
  Optional<JournalEntry> findByCompanyAndId(Company company, Long id);

  @EntityGraph(attributePaths = {"lines", "lines.account"})
  Optional<JournalEntry> findByCompanyAndReferenceNumber(Company company, String referenceNumber);

  @EntityGraph(attributePaths = {"lines", "lines.account"})
  Optional<JournalEntry> findFirstByCompanyAndReferenceNumberStartingWith(
      Company company, String referencePrefix);

  @EntityGraph(attributePaths = {"lines"})
  List<JournalEntry> findByCompanyAndReversalOfAndCorrectionReasonIgnoreCase(
      Company company, JournalEntry reversalOf, String correctionReason);

  @EntityGraph(attributePaths = {"lines", "lines.account"})
  List<JournalEntry> findByCompanyAndReversalOf(Company company, JournalEntry reversalOf);

  @EntityGraph(
      attributePaths = {
        "lines",
        "lines.account",
        "dealer",
        "supplier",
        "accountingPeriod",
        "reversalOf",
        "reversalEntry"
      })
  // Find all related entries for cascade reversal (e.g., INV-001 finds INV-001-COGS, INV-001-TAX)
  List<JournalEntry> findByCompanyAndReferenceNumberStartingWith(
      Company company, String referencePrefix);

  long countByCompanyAndEntryDateBetweenAndStatusIn(
      Company company, LocalDate start, LocalDate end, Collection<JournalEntryStatus> statuses);

  @EntityGraph(attributePaths = {"lines", "lines.account"})
  List<JournalEntry> findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
      Company company, LocalDate start, LocalDate end);

  @EntityGraph(attributePaths = {"lines", "lines.account"})
  List<JournalEntry> findByCompanyAndEntryDateAfterOrderByEntryDateAsc(
      Company company, LocalDate end);

  @Query("select je.company.id from JournalEntry je where je.id = :id")
  Optional<Long> findCompanyIdById(@Param("id") Long id);
}
