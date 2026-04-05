package com.bigbrightpaints.erp.core.auditaccess;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.auditaccess.dto.AuditFeedItemDto;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditDetailDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditListItemDto;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@Service
public class DefaultAuditAccessService implements AuditAccessService {

  private static final Comparator<AuditFeedItemDto> FEED_ORDER =
      Comparator.comparing(
              AuditFeedItemDto::occurredAt, Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(
              AuditFeedItemDto::sourceId, Comparator.nullsLast(Comparator.reverseOrder()));

  private final CompanyContextService companyContextService;
  private final AuditLogReadAdapter auditLogReadAdapter;
  private final BusinessAuditReadAdapter businessAuditReadAdapter;
  private final AccountingTransactionAuditReadAdapter accountingTransactionAuditReadAdapter;

  public DefaultAuditAccessService(
      CompanyContextService companyContextService,
      AuditLogReadAdapter auditLogReadAdapter,
      BusinessAuditReadAdapter businessAuditReadAdapter,
      AccountingTransactionAuditReadAdapter accountingTransactionAuditReadAdapter) {
    this.companyContextService = companyContextService;
    this.auditLogReadAdapter = auditLogReadAdapter;
    this.businessAuditReadAdapter = businessAuditReadAdapter;
    this.accountingTransactionAuditReadAdapter = accountingTransactionAuditReadAdapter;
  }

  @Override
  public PageResponse<AuditFeedItemDto> queryTenantAdminFeed(AuditFeedFilter filter) {
    validateMergedFeedWindow(filter);
    Company company = companyContextService.requireCurrentCompany();
    AuditFeedSlice tenantAuditLogs = auditLogReadAdapter.queryTenantCompanyFeed(company, filter);
    AuditFeedSlice tenantBusinessEvents =
        businessAuditReadAdapter.queryTenantCompanyFeed(company, filter);
    return merge(filter, tenantAuditLogs, tenantBusinessEvents);
  }

  @Override
  public PageResponse<AuditFeedItemDto> queryAccountingFeed(AuditFeedFilter filter) {
    validateMergedFeedWindow(filter);
    Company company = companyContextService.requireCurrentCompany();
    AuditFeedSlice accountingAuditLogs = auditLogReadAdapter.queryAccountingFeed(company, filter);
    AuditFeedSlice accountingBusinessEvents =
        businessAuditReadAdapter.queryAccountingFeed(company, filter);
    return merge(filter, accountingAuditLogs, accountingBusinessEvents);
  }

  @Override
  public PageResponse<AuditFeedItemDto> queryPlatformFeed(AuditFeedFilter filter) {
    AuditFeedSlice feed = auditLogReadAdapter.queryPlatformFeed(filter);
    return PageResponse.of(
        feed.items(), feed.totalElements(), filter.safePage(), filter.safeSize());
  }

  @Override
  public PageResponse<AccountingTransactionAuditListItemDto> queryAccountingTransactions(
      LocalDate from,
      LocalDate to,
      String module,
      String status,
      String reference,
      int page,
      int size) {
    return accountingTransactionAuditReadAdapter.listTransactions(
        from, to, module, status, reference, page, size);
  }

  @Override
  public AccountingTransactionAuditDetailDto getAccountingTransactionDetail(Long journalEntryId) {
    return accountingTransactionAuditReadAdapter.transactionDetail(journalEntryId);
  }

  private void validateMergedFeedWindow(AuditFeedFilter filter) {
    if (!filter.exceedsMergeWindow()) {
      return;
    }
    throw new ApplicationException(
            ErrorCode.VALIDATION_OUT_OF_RANGE,
            "Requested audit page exceeds the supported result window; refine filters or reduce"
                + " page size")
        .withDetail("page", filter.safePage())
        .withDetail("size", filter.safeSize())
        .withDetail("maxWindow", filter.maxMergeWindow());
  }

  private PageResponse<AuditFeedItemDto> merge(
      AuditFeedFilter filter, AuditFeedSlice left, AuditFeedSlice right) {
    int safePage = filter.safePage();
    int safeSize = filter.safeSize();
    List<AuditFeedItemDto> merged =
        Stream.concat(left.items().stream(), right.items().stream()).sorted(FEED_ORDER).toList();
    long offset = (long) safePage * safeSize;
    int start = (int) Math.min(offset, merged.size());
    int end = Math.min(start + safeSize, merged.size());
    return PageResponse.of(
        merged.subList(start, end),
        left.totalElements() + right.totalElements(),
        safePage,
        safeSize);
  }
}
