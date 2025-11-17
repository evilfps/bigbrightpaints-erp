package com.bigbrightpaints.erp.modules.accounting.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {
    @EntityGraph(attributePaths = {"lines", "lines.account"})
    List<JournalEntry> findByCompanyOrderByEntryDateDesc(Company company);
    List<JournalEntry> findByCompanyAndDealerOrderByEntryDateDesc(Company company, Dealer dealer);
    Optional<JournalEntry> findByCompanyAndId(Company company, Long id);
    Optional<JournalEntry> findByCompanyAndReferenceNumber(Company company, String referenceNumber);
    long countByCompanyAndEntryDateBetweenAndStatusIn(Company company, LocalDate start, LocalDate end, Collection<String> statuses);
}
