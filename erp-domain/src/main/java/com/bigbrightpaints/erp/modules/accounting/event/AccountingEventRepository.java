package com.bigbrightpaints.erp.modules.accounting.event;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountingEventRepository extends JpaRepository<AccountingEvent, Long> {

    // Find events by aggregate (for replay)
    List<AccountingEvent> findByAggregateIdOrderBySequenceNumberAsc(UUID aggregateId);

    // Get next sequence number for an aggregate
    @Query("SELECT COALESCE(MAX(e.sequenceNumber), 0) + 1 FROM AccountingEvent e WHERE e.aggregateId = :aggregateId")
    Long getNextSequenceNumber(@Param("aggregateId") UUID aggregateId);

    // Temporal queries - balance at a point in time
    Optional<AccountingEvent> findFirstByCompanyAndAccountIdAndEventTimestampLessThanEqualOrderByEventTimestampDescSequenceNumberDesc(
            Company company,
            Long accountId,
            Instant asOf);

    // Get balance at end of a specific date
    Optional<AccountingEvent> findFirstByCompanyAndAccountIdAndEffectiveDateLessThanEqualOrderByEffectiveDateDescEventTimestampDescSequenceNumberDesc(
            Company company,
            Long accountId,
            LocalDate asOfDate);

    // Account activity for a date range
    List<AccountingEvent> findByCompanyAndAccountIdAndEffectiveDateBetweenOrderByEventTimestampAsc(
            Company company, Long accountId, LocalDate startDate, LocalDate endDate);

    // Journal entry audit trail
    List<AccountingEvent> findByJournalEntryIdOrderByEventTimestampAsc(Long journalEntryId);

    // Events by type for a company
    Page<AccountingEvent> findByCompanyAndEventTypeOrderByEventTimestampDesc(
            Company company, AccountingEventType eventType, Pageable pageable);

    // All events in time range (for period reports)
    List<AccountingEvent> findByCompanyAndEffectiveDateBetweenOrderByEventTimestampAsc(
            Company company, LocalDate startDate, LocalDate endDate);

    // Correlation tracking (all events from same transaction)
    List<AccountingEvent> findByCorrelationIdOrderBySequenceNumberAsc(UUID correlationId);

    // Calculate running balance up to a timestamp
    @Query("""
        SELECT COALESCE(SUM(
            CASE WHEN e.eventType IN ('ACCOUNT_DEBIT_POSTED', 'JOURNAL_ENTRY_POSTED') 
                 THEN COALESCE(e.debitAmount, 0) - COALESCE(e.creditAmount, 0)
                 ELSE COALESCE(e.creditAmount, 0) - COALESCE(e.debitAmount, 0)
            END
        ), 0)
        FROM AccountingEvent e 
        WHERE e.company = :company 
        AND e.accountId = :accountId 
        AND e.eventTimestamp <= :asOf
        """)
    BigDecimal calculateBalanceAsOf(
            @Param("company") Company company,
            @Param("accountId") Long accountId,
            @Param("asOf") Instant asOf);

    // Get all account movements (debits and credits) for an account
    @Query("""
        SELECT e FROM AccountingEvent e 
        WHERE e.company = :company 
        AND e.accountId = :accountId 
        AND e.eventType IN ('ACCOUNT_DEBIT_POSTED', 'ACCOUNT_CREDIT_POSTED', 'JOURNAL_ENTRY_POSTED')
        ORDER BY e.effectiveDate, e.eventTimestamp
        """)
    List<AccountingEvent> findAccountMovements(
            @Param("company") Company company,
            @Param("accountId") Long accountId);
}
