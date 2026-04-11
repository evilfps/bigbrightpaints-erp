package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerBalanceView;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.service.CompanyScopedSalesLookupService;

import jakarta.transaction.Transactional;

@Service
public class DealerLedgerService extends AbstractPartnerLedgerService<Dealer, DealerLedgerEntry> {

  private final DealerLedgerRepository dealerLedgerRepository;
  private final CompanyContextService companyContextService;
  private final DealerRepository dealerRepository;
  private final CompanyScopedSalesLookupService salesLookupService;

  public DealerLedgerService(
      DealerLedgerRepository dealerLedgerRepository,
      CompanyContextService companyContextService,
      DealerRepository dealerRepository,
      CompanyScopedSalesLookupService salesLookupService) {
    this.dealerLedgerRepository = dealerLedgerRepository;
    this.companyContextService = companyContextService;
    this.dealerRepository = dealerRepository;
    this.salesLookupService = salesLookupService;
  }

  @Transactional
  @Override
  public void recordLedgerEntry(Dealer dealer, LedgerContext context) {
    super.recordLedgerEntry(dealer, context);
  }

  public Map<Long, BigDecimal> currentBalances(Collection<Long> dealerIds) {
    if (dealerIds == null || dealerIds.isEmpty()) {
      return Collections.emptyMap();
    }
    Company company = companyContextService.requireCurrentCompany();
    List<DealerBalanceView> aggregates =
        dealerLedgerRepository.aggregateBalances(company, dealerIds);
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
    Dealer dealer = salesLookupService.requireDealer(company, dealerId);
    return dealerLedgerRepository
        .aggregateBalance(company, dealer)
        .map(DealerBalanceView::balance)
        .orElse(BigDecimal.ZERO);
  }

  @Override
  protected DealerLedgerEntry createEntry() {
    return new DealerLedgerEntry();
  }

  @Override
  protected void persistEntry(DealerLedgerEntry entry) {
    dealerLedgerRepository.save(entry);
  }

  @Override
  protected Dealer reloadPartner(Dealer partner) {
    return dealerRepository
        .lockByCompanyAndId(partner.getCompany(), partner.getId())
        .orElse(partner);
  }

  @Override
  protected BigDecimal aggregateBalance(Dealer partner) {
    return dealerLedgerRepository
        .aggregateBalance(partner.getCompany(), partner)
        .map(DealerBalanceView::balance)
        .orElse(BigDecimal.ZERO);
  }

  @Override
  protected void updateOutstandingBalance(Dealer partner, BigDecimal balance) {
    BigDecimal normalized = balance != null ? balance : BigDecimal.ZERO;
    partner.setOutstandingBalance(normalized);
    dealerRepository.save(partner);
  }

  public List<DealerLedgerEntry> entries(Dealer dealer) {
    Company company = companyContextService.requireCurrentCompany();
    return dealerLedgerRepository.findByCompanyAndDealerOrderByEntryDateAsc(company, dealer);
  }

  @Transactional
  public void syncInvoiceLedger(Invoice invoice, LocalDate settlementDate) {
    if (invoice == null || invoice.getJournalEntry() == null) {
      return;
    }
    Company company = companyContextService.requireCurrentCompany();
    if (invoice.getCompany() != null
        && !Objects.equals(company.getId(), invoice.getCompany().getId())) {
      return;
    }
    List<DealerLedgerEntry> entries =
        dealerLedgerRepository.findByCompanyAndJournalEntry(company, invoice.getJournalEntry());
    if (entries.isEmpty()) {
      return;
    }
    BigDecimal total = normalize(invoice.getTotalAmount());
    BigDecimal outstanding = normalize(invoice.getOutstandingAmount());
    BigDecimal amountPaid = total.subtract(outstanding);
    if (amountPaid.compareTo(BigDecimal.ZERO) < 0) {
      amountPaid = BigDecimal.ZERO;
    }
    if (total.compareTo(BigDecimal.ZERO) > 0 && amountPaid.compareTo(total) > 0) {
      amountPaid = total;
    }
    String paymentStatus = resolvePaymentStatus(invoice, total, outstanding);
    LocalDate dueDate = invoice.getDueDate();
    String invoiceNumber = invoice.getInvoiceNumber();
    if (invoiceNumber != null) {
      invoiceNumber = invoiceNumber.trim();
    }
    boolean changed = false;
    for (DealerLedgerEntry entry : entries) {
      boolean entryChanged = false;
      if (!Objects.equals(entry.getInvoiceNumber(), invoiceNumber)) {
        entry.setInvoiceNumber(invoiceNumber);
        entryChanged = true;
      }
      if (!Objects.equals(entry.getDueDate(), dueDate)) {
        entry.setDueDate(dueDate);
        entryChanged = true;
      }
      if (!Objects.equals(entry.getPaymentStatus(), paymentStatus)) {
        entry.setPaymentStatus(paymentStatus);
        entryChanged = true;
      }
      if (entry.getAmountPaid() == null || entry.getAmountPaid().compareTo(amountPaid) != 0) {
        entry.setAmountPaid(amountPaid);
        entryChanged = true;
      }
      LocalDate targetPaidDate =
          resolvePaidDate(paymentStatus, settlementDate, entry.getPaidDate());
      if (!Objects.equals(entry.getPaidDate(), targetPaidDate)) {
        entry.setPaidDate(targetPaidDate);
        entryChanged = true;
      }
      if (entryChanged) {
        changed = true;
      }
    }
    if (changed) {
      dealerLedgerRepository.saveAll(entries);
    }
  }

  @Override
  protected void populateEntry(
      DealerLedgerEntry entry,
      Dealer partner,
      LedgerContext context,
      BigDecimal debit,
      BigDecimal credit) {
    entry.setCompany(partner.getCompany());
    entry.setDealer(partner);
    entry.setEntryDate(context.entryDate());
    entry.setReferenceNumber(context.referenceNumber());
    entry.setMemo(context.memo());
    entry.setJournalEntry(context.journalEntry());
    entry.setDebit(debit);
    entry.setCredit(credit);
  }

  private BigDecimal normalize(BigDecimal amount) {
    return amount != null ? amount : BigDecimal.ZERO;
  }

  private String resolvePaymentStatus(Invoice invoice, BigDecimal total, BigDecimal outstanding) {
    if (invoice != null && invoice.getStatus() != null) {
      if ("VOID".equalsIgnoreCase(invoice.getStatus())) {
        return "VOID";
      }
      if ("REVERSED".equalsIgnoreCase(invoice.getStatus())) {
        return "REVERSED";
      }
      if ("WRITTEN_OFF".equalsIgnoreCase(invoice.getStatus())) {
        return "WRITTEN_OFF";
      }
    }
    if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
      return "PAID";
    }
    if (total.compareTo(BigDecimal.ZERO) > 0 && outstanding.compareTo(total) < 0) {
      return "PARTIAL";
    }
    return "UNPAID";
  }

  private LocalDate resolvePaidDate(
      String paymentStatus, LocalDate settlementDate, LocalDate existingPaidDate) {
    if (!"PAID".equals(paymentStatus)) {
      return null;
    }
    return settlementDate != null ? settlementDate : existingPaidDate;
  }
}
