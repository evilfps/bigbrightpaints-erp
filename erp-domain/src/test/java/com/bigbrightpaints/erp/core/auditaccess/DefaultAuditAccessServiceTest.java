package com.bigbrightpaints.erp.core.auditaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.auditaccess.dto.AuditFeedItemDto;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditDetailDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditListItemDto;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@Tag("critical")
class DefaultAuditAccessServiceTest {

  @Test
  void queryTenantAdminFeed_mergesAuditLogsAndBusinessEventsInDescendingTimestampOrder() {
    CompanyContextService companyContextService = mock(CompanyContextService.class);
    AuditLogReadAdapter auditLogReadAdapter = mock(AuditLogReadAdapter.class);
    BusinessAuditReadAdapter businessAuditReadAdapter = mock(BusinessAuditReadAdapter.class);
    AccountingTransactionAuditReadAdapter transactionReadAdapter =
        mock(AccountingTransactionAuditReadAdapter.class);
    DefaultAuditAccessService service =
        new DefaultAuditAccessService(
            companyContextService,
            auditLogReadAdapter,
            businessAuditReadAdapter,
            transactionReadAdapter);
    Company company = new Company();
    ReflectionTestUtils.setField(company, "id", 7L);
    company.setCode("TENANT-A");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    AuditFeedFilter filter =
        new AuditFeedFilter(
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            2);
    when(auditLogReadAdapter.queryTenantCompanyFeed(company, filter))
        .thenReturn(
            new AuditFeedSlice(
                List.of(
                    new AuditFeedItemDto(
                        1L,
                        "AUDIT_LOG",
                        "SECURITY",
                        Instant.parse("2026-03-01T09:00:00Z"),
                        7L,
                        "TENANT-A",
                        "AUTH",
                        "LOGIN_SUCCESS",
                        "SUCCESS",
                        null,
                        "ops@example.com",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "POST",
                        "/api/v1/auth/login",
                        "trace-1",
                        Map.of())),
                1));
    when(businessAuditReadAdapter.queryTenantCompanyFeed(company, filter))
        .thenReturn(
            new AuditFeedSlice(
                List.of(
                    new AuditFeedItemDto(
                        2L,
                        "BUSINESS_EVENT",
                        "ACCOUNTING",
                        Instant.parse("2026-03-01T10:00:00Z"),
                        7L,
                        "TENANT-A",
                        "ACCOUNTING",
                        "JOURNAL_ENTRY_POSTED",
                        "SUCCESS",
                        42L,
                        "ops@example.com",
                        null,
                        null,
                        "JOURNAL_ENTRY",
                        "17",
                        "JE-17",
                        null,
                        null,
                        "trace-2",
                        Map.of())),
                1));

    PageResponse<AuditFeedItemDto> page = service.queryTenantAdminFeed(filter);

    assertThat(page.totalElements()).isEqualTo(2);
    assertThat(page.content()).extracting(AuditFeedItemDto::sourceId).containsExactly(2L, 1L);
  }

  @Test
  void queryAccountingFeed_mergesAuditLogsAndBusinessEventsInDescendingTimestampOrder() {
    CompanyContextService companyContextService = mock(CompanyContextService.class);
    AuditLogReadAdapter auditLogReadAdapter = mock(AuditLogReadAdapter.class);
    BusinessAuditReadAdapter businessAuditReadAdapter = mock(BusinessAuditReadAdapter.class);
    AccountingTransactionAuditReadAdapter transactionReadAdapter =
        mock(AccountingTransactionAuditReadAdapter.class);
    DefaultAuditAccessService service =
        new DefaultAuditAccessService(
            companyContextService,
            auditLogReadAdapter,
            businessAuditReadAdapter,
            transactionReadAdapter);
    Company company = new Company();
    ReflectionTestUtils.setField(company, "id", 7L);
    company.setCode("TENANT-A");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    AuditFeedFilter filter =
        new AuditFeedFilter(
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            2);
    when(auditLogReadAdapter.queryAccountingFeed(company, filter))
        .thenReturn(
            new AuditFeedSlice(
                List.of(
                    new AuditFeedItemDto(
                        1L,
                        "AUDIT_LOG",
                        "BUSINESS",
                        Instant.parse("2026-03-01T09:00:00Z"),
                        7L,
                        "TENANT-A",
                        "ACCOUNTING",
                        "PAYROLL_POSTED",
                        "SUCCESS",
                        null,
                        "ops@example.com",
                        null,
                        null,
                        "PAYROLL_RUN",
                        "17",
                        "17",
                        "POST",
                        "/api/v1/hr/payroll/17/post",
                        "trace-1",
                        Map.of())),
                1));
    when(businessAuditReadAdapter.queryAccountingFeed(company, filter))
        .thenReturn(
            new AuditFeedSlice(
                List.of(
                    new AuditFeedItemDto(
                        2L,
                        "BUSINESS_EVENT",
                        "ACCOUNTING",
                        Instant.parse("2026-03-01T10:00:00Z"),
                        7L,
                        "TENANT-A",
                        "ACCOUNTING",
                        "JOURNAL_ENTRY_POSTED",
                        "SUCCESS",
                        42L,
                        "ops@example.com",
                        null,
                        null,
                        "JOURNAL_ENTRY",
                        "17",
                        "JE-17",
                        null,
                        null,
                        "trace-2",
                        Map.of())),
                1));

    PageResponse<AuditFeedItemDto> page = service.queryAccountingFeed(filter);

    assertThat(page.totalElements()).isEqualTo(2);
    assertThat(page.content()).extracting(AuditFeedItemDto::sourceId).containsExactly(2L, 1L);
  }

  @Test
  void accountingTransactionQueries_delegateToCanonicalTransactionAdapter() {
    CompanyContextService companyContextService = mock(CompanyContextService.class);
    AuditLogReadAdapter auditLogReadAdapter = mock(AuditLogReadAdapter.class);
    BusinessAuditReadAdapter businessAuditReadAdapter = mock(BusinessAuditReadAdapter.class);
    AccountingTransactionAuditReadAdapter transactionReadAdapter =
        mock(AccountingTransactionAuditReadAdapter.class);
    DefaultAuditAccessService service =
        new DefaultAuditAccessService(
            companyContextService,
            auditLogReadAdapter,
            businessAuditReadAdapter,
            transactionReadAdapter);
    PageResponse<AccountingTransactionAuditListItemDto> expectedPage =
        PageResponse.of(List.of(), 0, 0, 50);
    AccountingTransactionAuditDetailDto expectedDetail =
        new AccountingTransactionAuditDetailDto(
            17L,
            java.util.UUID.randomUUID(),
            "JE-17",
            LocalDate.of(2026, 3, 5),
            "POSTED",
            "ACCOUNTING",
            "JOURNAL",
            "memo",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            java.math.BigDecimal.TEN,
            java.math.BigDecimal.TEN,
            "OK",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            List.of(),
            Instant.parse("2026-03-05T10:00:00Z"),
            Instant.parse("2026-03-05T10:00:00Z"),
            Instant.parse("2026-03-05T10:00:00Z"),
            "ops@example.com",
            "ops@example.com",
            "ops@example.com");
    when(transactionReadAdapter.listTransactions(
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            "ACCOUNTING",
            "POSTED",
            "JE-17",
            0,
            50))
        .thenReturn(expectedPage);
    when(transactionReadAdapter.transactionDetail(17L)).thenReturn(expectedDetail);

    assertThat(
            service.queryAccountingTransactions(
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31),
                "ACCOUNTING",
                "POSTED",
                "JE-17",
                0,
                50))
        .isEqualTo(expectedPage);
    assertThat(service.getAccountingTransactionDetail(17L)).isEqualTo(expectedDetail);
    verify(transactionReadAdapter)
        .listTransactions(
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            "ACCOUNTING",
            "POSTED",
            "JE-17",
            0,
            50);
    verify(transactionReadAdapter).transactionDetail(17L);
  }

  @Test
  void queryPlatformFeed_delegatesToAuditLogReadAdapter() {
    CompanyContextService companyContextService = mock(CompanyContextService.class);
    AuditLogReadAdapter auditLogReadAdapter = mock(AuditLogReadAdapter.class);
    BusinessAuditReadAdapter businessAuditReadAdapter = mock(BusinessAuditReadAdapter.class);
    AccountingTransactionAuditReadAdapter transactionReadAdapter =
        mock(AccountingTransactionAuditReadAdapter.class);
    DefaultAuditAccessService service =
        new DefaultAuditAccessService(
            companyContextService,
            auditLogReadAdapter,
            businessAuditReadAdapter,
            transactionReadAdapter);
    AuditFeedFilter filter =
        new AuditFeedFilter(null, null, null, null, null, null, null, null, 2, 25);
    AuditFeedSlice feed = new AuditFeedSlice(List.of(), 0);
    when(auditLogReadAdapter.queryPlatformFeed(filter)).thenReturn(feed);

    PageResponse<AuditFeedItemDto> page = service.queryPlatformFeed(filter);

    assertThat(page.content()).isEmpty();
    assertThat(page.page()).isEqualTo(2);
    assertThat(page.size()).isEqualTo(25);
    assertThat(page.totalElements()).isZero();
    verify(auditLogReadAdapter).queryPlatformFeed(filter);
  }

  @Test
  void queryTenantAdminFeed_rejectsWindowsLargerThanTheSupportedMergeLimit() {
    CompanyContextService companyContextService = mock(CompanyContextService.class);
    AuditLogReadAdapter auditLogReadAdapter = mock(AuditLogReadAdapter.class);
    BusinessAuditReadAdapter businessAuditReadAdapter = mock(BusinessAuditReadAdapter.class);
    AccountingTransactionAuditReadAdapter transactionReadAdapter =
        mock(AccountingTransactionAuditReadAdapter.class);
    DefaultAuditAccessService service =
        new DefaultAuditAccessService(
            companyContextService,
            auditLogReadAdapter,
            businessAuditReadAdapter,
            transactionReadAdapter);
    AuditFeedFilter filter =
        new AuditFeedFilter(null, null, null, null, null, null, null, null, 30, 200);

    assertThatThrownBy(() -> service.queryTenantAdminFeed(filter))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_OUT_OF_RANGE);
              assertThat(ex.getDetails())
                  .containsEntry("page", 30)
                  .containsEntry("size", 200)
                  .containsEntry("maxWindow", 5000);
            });
  }

  @Test
  void queryAccountingFeed_rejectsWindowsLargerThanTheSupportedMergeLimit() {
    CompanyContextService companyContextService = mock(CompanyContextService.class);
    AuditLogReadAdapter auditLogReadAdapter = mock(AuditLogReadAdapter.class);
    BusinessAuditReadAdapter businessAuditReadAdapter = mock(BusinessAuditReadAdapter.class);
    AccountingTransactionAuditReadAdapter transactionReadAdapter =
        mock(AccountingTransactionAuditReadAdapter.class);
    DefaultAuditAccessService service =
        new DefaultAuditAccessService(
            companyContextService,
            auditLogReadAdapter,
            businessAuditReadAdapter,
            transactionReadAdapter);
    AuditFeedFilter filter =
        new AuditFeedFilter(null, null, "ACCOUNTING", null, null, null, null, null, 30, 200);

    assertThatThrownBy(() -> service.queryAccountingFeed(filter))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_OUT_OF_RANGE);
              assertThat(ex.getDetails())
                  .containsEntry("page", 30)
                  .containsEntry("size", 200)
                  .containsEntry("maxWindow", 5000);
            });
  }
}
