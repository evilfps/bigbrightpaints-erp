package com.bigbrightpaints.erp.modules.accounting.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface JournalLineRepository extends JpaRepository<JournalLine, Long> {

    interface JournalEntryLineTotals {
        Long getJournalEntryId();

        BigDecimal getTotalDebit();

        BigDecimal getTotalCredit();
    }

    @Query("""
            select line from JournalLine line
            join fetch line.journalEntry entry
            where entry.company = :company
              and line.account.id = :accountId
              and entry.status = 'POSTED'
              and entry.entryDate between :start and :end
            order by entry.entryDate asc, entry.referenceNumber asc, line.id asc
            """)
    List<JournalLine> findLinesForAccountBetween(@Param("company") Company company,
                                                 @Param("accountId") Long accountId,
                                                 @Param("start") LocalDate start,
                                                 @Param("end") LocalDate end);

    @Query("""
            select line.account.type, sum(line.debit), sum(line.credit)
            from JournalLine line
            join line.journalEntry entry
            where entry.company = :company
              and entry.status = 'POSTED'
              and entry.entryDate between :start and :end
            group by line.account.type
            """)
    List<Object[]> summarizeByAccountType(@Param("company") Company company,
                                          @Param("start") LocalDate start,
                                          @Param("end") LocalDate end);

    @Query("""
            select line.account.id, sum(line.debit), sum(line.credit)
            from JournalLine line
            join line.journalEntry entry
            where entry.company = :company
              and entry.status = 'POSTED'
              and entry.entryDate between :start and :end
            group by line.account.id
            """)
    List<Object[]> summarizeByAccountWithin(@Param("company") Company company,
                                            @Param("start") LocalDate start,
                                            @Param("end") LocalDate end);

    @Query("""
            select line.account.id, sum(line.debit), sum(line.credit)
            from JournalLine line
            join line.journalEntry entry
            where entry.company = :company
              and entry.status = 'POSTED'
              and entry.entryDate between :start and :end
              and entry.sourceModule = 'ACCOUNTING_PERIOD'
              and entry.referenceNumber like 'PERIOD-CLOSE-%'
              and entry.sourceReference = entry.referenceNumber
            group by line.account.id
            """)
    List<Object[]> summarizePostedPeriodCloseSystemJournalsByAccountWithin(@Param("company") Company company,
                                                                           @Param("start") LocalDate start,
                                                                           @Param("end") LocalDate end);

    @Query("""
            select line.account.id, sum(line.debit), sum(line.credit)
            from JournalLine line
            join line.journalEntry entry
            where entry.company = :company
              and entry.status = 'POSTED'
              and entry.entryDate <= :end
            group by line.account.id
            """)
    List<Object[]> summarizeByAccountUpTo(@Param("company") Company company,
                                          @Param("end") LocalDate end);

    @Query("""
            select coalesce(sum(line.debit), 0) - coalesce(sum(line.credit), 0)
            from JournalLine line
            join line.journalEntry entry
            where entry.company = :company
              and entry.status = 'POSTED'
              and line.account.id = :accountId
              and entry.entryDate <= :end
            """)
    BigDecimal netBalanceUpTo(@Param("company") Company company,
                              @Param("accountId") Long accountId,
                              @Param("end") LocalDate end);

    interface AccountLineTotals {
        Long getAccountId();

        BigDecimal getTotalDebit();

        BigDecimal getTotalCredit();
    }

    @Query("""
            select line.account.id as accountId,
                   coalesce(sum(line.debit), 0) as totalDebit,
                   coalesce(sum(line.credit), 0) as totalCredit
            from JournalLine line
            join line.journalEntry entry
            where entry.company = :company
              and entry.status = :status
              and line.account.id in :accountIds
              and entry.entryDate between :start and :end
            group by line.account.id
            """)
    List<AccountLineTotals> summarizeTotalsByCompanyAndAccountIdsWithin(
            @Param("company") Company company,
            @Param("accountIds") Collection<Long> accountIds,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end,
            @Param("status") String status);

    @Query("""
            select line.journalEntry.id as journalEntryId,
                   coalesce(sum(line.debit), 0) as totalDebit,
                   coalesce(sum(line.credit), 0) as totalCredit
            from JournalLine line
            join line.journalEntry entry
            where entry.company = :company
              and line.journalEntry.id in :journalEntryIds
            group by line.journalEntry.id
            """)
    List<JournalEntryLineTotals> summarizeTotalsByCompanyAndJournalEntryIds(@Param("company") Company company,
                                                                             @Param("journalEntryIds") Collection<Long> journalEntryIds);
}
