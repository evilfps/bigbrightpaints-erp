package com.bigbrightpaints.erp.core.auditaccess;

import java.time.LocalDate;

import com.bigbrightpaints.erp.core.auditaccess.dto.AuditFeedItemDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditDetailDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditListItemDto;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

public interface AuditAccessService {

  PageResponse<AuditFeedItemDto> queryTenantAdminFeed(AuditFeedFilter filter);

  PageResponse<AuditFeedItemDto> queryAccountingFeed(AuditFeedFilter filter);

  PageResponse<AuditFeedItemDto> queryPlatformFeed(AuditFeedFilter filter);

  PageResponse<AccountingTransactionAuditListItemDto> queryAccountingTransactions(
      LocalDate from,
      LocalDate to,
      String module,
      String status,
      String reference,
      int page,
      int size);

  AccountingTransactionAuditDetailDto getAccountingTransactionDetail(Long journalEntryId);
}
