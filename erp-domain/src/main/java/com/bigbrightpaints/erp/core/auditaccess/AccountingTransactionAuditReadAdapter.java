package com.bigbrightpaints.erp.core.auditaccess;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditDetailDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditListItemDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingAuditTrailService;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@Component
public class AccountingTransactionAuditReadAdapter {

  private final AccountingAuditTrailService accountingAuditTrailService;

  public AccountingTransactionAuditReadAdapter(
      AccountingAuditTrailService accountingAuditTrailService) {
    this.accountingAuditTrailService = accountingAuditTrailService;
  }

  public PageResponse<AccountingTransactionAuditListItemDto> listTransactions(
      LocalDate from,
      LocalDate to,
      String module,
      String status,
      String reference,
      int page,
      int size) {
    return accountingAuditTrailService.listTransactions(
        from, to, module, status, reference, page, size);
  }

  public AccountingTransactionAuditDetailDto transactionDetail(Long journalEntryId) {
    return accountingAuditTrailService.transactionDetail(journalEntryId);
  }
}
