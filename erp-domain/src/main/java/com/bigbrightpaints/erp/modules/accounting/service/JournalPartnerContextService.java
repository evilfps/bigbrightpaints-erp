package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

@Service
class JournalPartnerContextService {

  private final DealerRepository dealerRepository;
  private final SupplierRepository supplierRepository;
  private final AccountResolutionService accountResolutionService;
  private final DealerLedgerService dealerLedgerService;
  private final SupplierLedgerService supplierLedgerService;

  JournalPartnerContextService(
      DealerRepository dealerRepository,
      SupplierRepository supplierRepository,
      AccountResolutionService accountResolutionService,
      DealerLedgerService dealerLedgerService,
      SupplierLedgerService supplierLedgerService) {
    this.dealerRepository = dealerRepository;
    this.supplierRepository = supplierRepository;
    this.accountResolutionService = accountResolutionService;
    this.dealerLedgerService = dealerLedgerService;
    this.supplierLedgerService = supplierLedgerService;
  }

  ResolvedPartnerContext resolve(
      Company company, Dealer dealer, Supplier supplier, List<Account> accounts) {
    Map<Long, Set<Long>> dealerOwnerByReceivableAccountId = new HashMap<>();
    Map<Long, Set<Long>> supplierOwnerByPayableAccountId = new HashMap<>();
    boolean hasReceivableAccount =
        loadDealerOwners(company, accounts, dealerOwnerByReceivableAccountId);
    boolean hasPayableAccount =
        loadSupplierOwners(company, accounts, supplierOwnerByPayableAccountId);
    validatePartnerContexts(
        dealer,
        supplier,
        accounts,
        dealerOwnerByReceivableAccountId,
        supplierOwnerByPayableAccountId);
    Account dealerReceivableAccount = dealer != null ? dealer.getReceivableAccount() : null;
    Account supplierPayableAccount = supplier != null ? supplier.getPayableAccount() : null;
    if (dealer != null && hasReceivableAccount && dealerReceivableAccount == null) {
      dealerReceivableAccount = accountResolutionService.requireDealerReceivable(dealer);
    }
    if (supplier != null && hasPayableAccount && supplierPayableAccount == null) {
      supplierPayableAccount = accountResolutionService.requireSupplierPayable(supplier);
    }
    return new ResolvedPartnerContext(
        dealer,
        supplier,
        dealerReceivableAccount,
        supplierPayableAccount,
        hasReceivableAccount,
        hasPayableAccount);
  }

  void recordLedgerEntries(
      JournalEntry saved, Account dealerReceivableAccount, Account supplierPayableAccount) {
    if (saved.getDealer() != null && dealerReceivableAccount != null) {
      BigDecimal dealerDebit = BigDecimal.ZERO;
      BigDecimal dealerCredit = BigDecimal.ZERO;
      for (JournalLine line : saved.getLines()) {
        if (line.getAccount() != null
            && Objects.equals(line.getAccount().getId(), dealerReceivableAccount.getId())) {
          dealerDebit = dealerDebit.add(line.getDebit());
          dealerCredit = dealerCredit.add(line.getCredit());
        }
      }
      if (dealerDebit.compareTo(BigDecimal.ZERO) != 0
          || dealerCredit.compareTo(BigDecimal.ZERO) != 0) {
        dealerLedgerService.recordLedgerEntry(
            saved.getDealer(),
            new AbstractPartnerLedgerService.LedgerContext(
                saved.getEntryDate(),
                saved.getReferenceNumber(),
                saved.getMemo(),
                dealerDebit,
                dealerCredit,
                saved));
      }
    }
    if (saved.getSupplier() != null && supplierPayableAccount != null) {
      BigDecimal supplierDebit = BigDecimal.ZERO;
      BigDecimal supplierCredit = BigDecimal.ZERO;
      for (JournalLine line : saved.getLines()) {
        if (line.getAccount() != null
            && Objects.equals(line.getAccount().getId(), supplierPayableAccount.getId())) {
          supplierDebit = supplierDebit.add(line.getDebit());
          supplierCredit = supplierCredit.add(line.getCredit());
        }
      }
      if (supplierDebit.compareTo(BigDecimal.ZERO) != 0
          || supplierCredit.compareTo(BigDecimal.ZERO) != 0) {
        supplierLedgerService.recordLedgerEntry(
            saved.getSupplier(),
            new AbstractPartnerLedgerService.LedgerContext(
                saved.getEntryDate(),
                saved.getReferenceNumber(),
                saved.getMemo(),
                supplierDebit,
                supplierCredit,
                saved));
      }
    }
  }

  private boolean loadDealerOwners(
      Company company,
      List<Account> accounts,
      Map<Long, Set<Long>> dealerOwnerByReceivableAccountId) {
    List<Account> receivableAccounts =
        accounts.stream().filter(accountResolutionService::isReceivableAccount).toList();
    if (receivableAccounts.isEmpty()) {
      return false;
    }
    for (Dealer owner :
        dealerRepository.findByCompanyAndReceivableAccountIn(company, receivableAccounts)) {
      if (owner.getReceivableAccount() == null
          || owner.getReceivableAccount().getId() == null
          || owner.getId() == null) {
        continue;
      }
      dealerOwnerByReceivableAccountId
          .computeIfAbsent(owner.getReceivableAccount().getId(), ignored -> new HashSet<>())
          .add(owner.getId());
    }
    return true;
  }

  private boolean loadSupplierOwners(
      Company company,
      List<Account> accounts,
      Map<Long, Set<Long>> supplierOwnerByPayableAccountId) {
    List<Account> payableAccounts =
        accounts.stream().filter(accountResolutionService::isPayableAccount).toList();
    if (payableAccounts.isEmpty()) {
      return false;
    }
    for (Supplier owner :
        supplierRepository.findByCompanyAndPayableAccountIn(company, payableAccounts)) {
      Account payableAccount = owner.getPayableAccount();
      if (!isSupplierOwnedPayableAccount(payableAccount)
          || payableAccount.getId() == null
          || owner.getId() == null) {
        continue;
      }
      supplierOwnerByPayableAccountId
          .computeIfAbsent(payableAccount.getId(), ignored -> new HashSet<>())
          .add(owner.getId());
    }
    return !supplierOwnerByPayableAccountId.isEmpty();
  }

  private boolean isSupplierOwnedPayableAccount(Account payableAccount) {
    if (payableAccount == null) {
      return false;
    }
    // Supplier-owned AP context applies to supplier payable subaccounts, not the shared AP
    // control account used for generic accrual/adjustment postings.
    return payableAccount.getParent() != null;
  }

  private void validatePartnerContexts(
      Dealer dealer,
      Supplier supplier,
      List<Account> accounts,
      Map<Long, Set<Long>> dealerOwnerByReceivableAccountId,
      Map<Long, Set<Long>> supplierOwnerByPayableAccountId) {
    for (Account account : accounts) {
      Long accountId = account.getId();
      if (accountId == null) {
        continue;
      }
      Set<Long> dealerOwnerIds = dealerOwnerByReceivableAccountId.get(accountId);
      if (dealerOwnerIds != null && !dealerOwnerIds.isEmpty()) {
        if (dealer == null || dealer.getId() == null || !dealerOwnerIds.contains(dealer.getId())) {
          throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_REFERENCE,
              "Dealer receivable account "
                  + account.getCode()
                  + " requires matching dealer context");
        }
      }
      Set<Long> supplierOwnerIds = supplierOwnerByPayableAccountId.get(accountId);
      if (supplierOwnerIds != null && !supplierOwnerIds.isEmpty()) {
        if (supplier == null
            || supplier.getId() == null
            || !supplierOwnerIds.contains(supplier.getId())) {
          throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_REFERENCE,
              "Supplier payable account "
                  + account.getCode()
                  + " requires matching supplier context");
        }
      }
    }
  }

  record ResolvedPartnerContext(
      Dealer dealer,
      Supplier supplier,
      Account dealerReceivableAccount,
      Account supplierPayableAccount,
      boolean hasReceivableAccount,
      boolean hasPayableAccount) {}
}
