package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerBalanceView;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class DealerLedgerService {

    private final DealerLedgerRepository dealerLedgerRepository;
    private final CompanyContextService companyContextService;
    private final DealerRepository dealerRepository;

    public DealerLedgerService(DealerLedgerRepository dealerLedgerRepository,
                               CompanyContextService companyContextService,
                               DealerRepository dealerRepository) {
        this.dealerLedgerRepository = dealerLedgerRepository;
        this.companyContextService = companyContextService;
        this.dealerRepository = dealerRepository;
    }

    public void recordLedgerEntry(Dealer dealer,
                                  LocalLedgerContext context) {
        Objects.requireNonNull(dealer, "Dealer is required for ledger entry");
        BigDecimal debit = context.debit();
        BigDecimal credit = context.credit();
        if ((debit == null || debit.compareTo(BigDecimal.ZERO) == 0)
                && (credit == null || credit.compareTo(BigDecimal.ZERO) == 0)) {
            return;
        }
        DealerLedgerEntry entry = new DealerLedgerEntry();
        entry.setCompany(dealer.getCompany());
        entry.setDealer(dealer);
        entry.setEntryDate(context.entryDate());
        entry.setReferenceNumber(context.referenceNumber());
        entry.setMemo(context.memo());
        entry.setJournalEntry(context.journalEntry());
        entry.setDebit(debit == null ? BigDecimal.ZERO : debit);
        entry.setCredit(credit == null ? BigDecimal.ZERO : credit);
        dealerLedgerRepository.save(entry);
        Dealer managedDealer = dealerRepository.findById(dealer.getId()).orElse(dealer);
        BigDecimal aggregate = dealerLedgerRepository.aggregateBalance(managedDealer.getCompany(), managedDealer)
                .map(DealerBalanceView::balance)
                .orElse(BigDecimal.ZERO);
        managedDealer.setOutstandingBalance(aggregate);
        dealerRepository.save(managedDealer);
    }

    public Map<Long, BigDecimal> currentBalances(Collection<Long> dealerIds) {
        if (dealerIds == null || dealerIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Company company = companyContextService.requireCurrentCompany();
        List<DealerBalanceView> aggregates = dealerLedgerRepository.aggregateBalances(company, dealerIds);
        Map<Long, BigDecimal> balanceMap = new HashMap<>();
        for (DealerBalanceView view : aggregates) {
            balanceMap.put(view.dealerId(), view.balance());
        }
        return balanceMap;
    }

    public BigDecimal currentBalance(Long dealerId) {
        if (dealerId == null) {
            return BigDecimal.ZERO;
        }
        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = dealerRepository.findByCompanyAndId(company, dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Dealer not found"));
        return dealerLedgerRepository.aggregateBalance(company, dealer)
                .map(DealerBalanceView::balance)
                .orElse(BigDecimal.ZERO);
    }

    public record LocalLedgerContext(LocalDate entryDate,
                                     String referenceNumber,
                                     String memo,
                                     BigDecimal debit,
                                     BigDecimal credit,
                                     JournalEntry journalEntry) {}
}
