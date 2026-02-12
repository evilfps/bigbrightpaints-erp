package com.bigbrightpaints.erp.modules.accounting.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerBalanceView;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DealerLedgerRepository extends JpaRepository<DealerLedgerEntry, Long> {

    List<DealerLedgerEntry> findByCompanyAndDealerOrderByEntryDateAsc(Company company, Dealer dealer);

    @Query("select new com.bigbrightpaints.erp.modules.accounting.dto.DealerBalanceView(e.dealer.id, coalesce(sum(e.debit - e.credit), 0)) " +
            "from DealerLedgerEntry e where e.company = :company and e.dealer.id in :dealerIds group by e.dealer.id")
    List<DealerBalanceView> aggregateBalances(@Param("company") Company company,
                                              @Param("dealerIds") Collection<Long> dealerIds);

    @Query("select new com.bigbrightpaints.erp.modules.accounting.dto.DealerBalanceView(e.dealer.id, coalesce(sum(e.debit - e.credit), 0)) " +
            "from DealerLedgerEntry e where e.company = :company and e.dealer.id in :dealerIds " +
            "and e.entryDate between :start and :end group by e.dealer.id")
    List<DealerBalanceView> aggregateBalancesBetween(@Param("company") Company company,
                                                     @Param("dealerIds") Collection<Long> dealerIds,
                                                     @Param("start") java.time.LocalDate start,
                                                     @Param("end") java.time.LocalDate end);

    @Query("select new com.bigbrightpaints.erp.modules.accounting.dto.DealerBalanceView(e.dealer.id, coalesce(sum(e.debit - e.credit), 0)) " +
            "from DealerLedgerEntry e where e.company = :company and e.dealer.id in :dealerIds " +
            "and e.entryDate <= :end group by e.dealer.id")
    List<DealerBalanceView> aggregateBalancesUpTo(@Param("company") Company company,
                                                  @Param("dealerIds") Collection<Long> dealerIds,
                                                  @Param("end") java.time.LocalDate end);

    @Query("select new com.bigbrightpaints.erp.modules.accounting.dto.DealerBalanceView(e.dealer.id, coalesce(sum(e.debit - e.credit), 0)) " +
            "from DealerLedgerEntry e where e.company = :company and e.dealer = :dealer group by e.dealer.id")
    Optional<DealerBalanceView> aggregateBalance(@Param("company") Company company, @Param("dealer") Dealer dealer);

    @Query("select new com.bigbrightpaints.erp.modules.accounting.dto.DealerBalanceView(e.dealer.id, coalesce(sum(e.debit - e.credit), 0)) " +
            "from DealerLedgerEntry e where e.company = :company and e.dealer = :dealer and e.entryDate < :before group by e.dealer.id")
    Optional<DealerBalanceView> aggregateBalanceBefore(@Param("company") Company company,
                                                       @Param("dealer") Dealer dealer,
                                                       @Param("before") java.time.LocalDate before);

    List<DealerLedgerEntry> findByCompanyAndJournalEntry(Company company, JournalEntry journalEntry);

    List<DealerLedgerEntry> findByCompanyAndDealerAndEntryDateBetweenOrderByEntryDateAscIdAsc(Company company,
                                                                                               Dealer dealer,
                                                                                               java.time.LocalDate start,
                                                                                               java.time.LocalDate end);

    List<DealerLedgerEntry> findByCompanyAndDealerAndEntryDateBetweenOrderByEntryDateAsc(Company company,
                                                                                         Dealer dealer,
                                                                                         java.time.LocalDate start,
                                                                                         java.time.LocalDate end);

    List<DealerLedgerEntry> findByCompanyAndDealerAndEntryDateBeforeOrderByEntryDateAsc(Company company,
                                                                                        Dealer dealer,
                                                                                        java.time.LocalDate before);

    List<DealerLedgerEntry> findByCompanyAndDealerAndEntryDateLessThanEqualOrderByEntryDateAscIdAsc(Company company,
                                                                                                      Dealer dealer,
                                                                                                      java.time.LocalDate asOf);

    java.util.Optional<DealerLedgerEntry> findFirstByCompanyAndDealerOrderByEntryDateDescIdDesc(Company company, Dealer dealer);

    // Aging report queries
    @Query("SELECT e FROM DealerLedgerEntry e WHERE e.company = :company AND e.dealer = :dealer " +
           "AND e.paymentStatus NOT IN ('PAID','VOID','REVERSED') AND e.invoiceNumber IS NOT NULL ORDER BY e.dueDate ASC")
    List<DealerLedgerEntry> findUnpaidByDealer(@Param("company") Company company, @Param("dealer") Dealer dealer);

    @Query("SELECT e FROM DealerLedgerEntry e WHERE e.company = :company " +
           "AND e.paymentStatus NOT IN ('PAID','VOID','REVERSED') AND e.invoiceNumber IS NOT NULL ORDER BY e.dealer.id, e.dueDate ASC")
    List<DealerLedgerEntry> findAllUnpaid(@Param("company") Company company);

    @Query("SELECT e FROM DealerLedgerEntry e WHERE e.company = :company " +
           "AND e.entryDate <= :asOfDate " +
           "AND e.paymentStatus NOT IN ('PAID','VOID','REVERSED') " +
           "AND e.invoiceNumber IS NOT NULL ORDER BY e.dealer.id, e.dueDate ASC")
    List<DealerLedgerEntry> findAllUnpaidAsOf(@Param("company") Company company,
                                              @Param("asOfDate") java.time.LocalDate asOfDate);

    @Query("SELECT e FROM DealerLedgerEntry e WHERE e.company = :company " +
           "AND e.paymentStatus NOT IN ('PAID','VOID','REVERSED') AND e.invoiceNumber IS NOT NULL AND e.dueDate < :asOfDate ORDER BY e.dealer.id, e.dueDate ASC")
    List<DealerLedgerEntry> findOverdueAsOf(@Param("company") Company company, @Param("asOfDate") java.time.LocalDate asOfDate);

    // DSO calculation support - using native query for date arithmetic compatibility
    @Query(value = "SELECT AVG(EXTRACT(EPOCH FROM (paid_date - entry_date)) / 86400) " +
           "FROM dealer_ledger_entries WHERE company_id = :companyId AND dealer_id = :dealerId AND paid_date IS NOT NULL", 
           nativeQuery = true)
    Double calculateAverageDSO(@Param("companyId") Long companyId, @Param("dealerId") Long dealerId);

    // Find by invoice for payment matching
    List<DealerLedgerEntry> findByCompanyAndInvoiceNumber(Company company, String invoiceNumber);
}
