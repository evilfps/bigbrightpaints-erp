package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.bigbrightpaints.erp.core.auditaccess.AuditAccessService;
import com.bigbrightpaints.erp.core.auditaccess.AuditFeedFilter;
import com.bigbrightpaints.erp.core.auditaccess.dto.AuditFeedItemDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditDetailDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditListItemDto;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

class AccountingAuditControllerTest {

  @Test
  void listEvents_parsesCanonicalAuditFeedFilters() {
    AuditAccessService auditAccessService = mock(AuditAccessService.class);
    AccountingAuditController controller = new AccountingAuditController(auditAccessService);
    PageResponse<AuditFeedItemDto> expected =
        PageResponse.of(
            List.of(
                new AuditFeedItemDto(
                    1L,
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
                    "trace-1",
                    Map.of())),
            1,
            1,
            25);
    AuditFeedFilter expectedFilter =
        new AuditFeedFilter(
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            "ACCOUNTING",
            "JOURNAL_ENTRY_POSTED",
            "SUCCESS",
            "ops@example.com",
            "JOURNAL_ENTRY",
            "JE-17",
            1,
            25);
    when(auditAccessService.queryAccountingFeed(expectedFilter)).thenReturn(expected);

    ApiResponse<PageResponse<AuditFeedItemDto>> body =
        controller
            .listEvents(
                " 2026-03-01 ",
                "2026-03-31 ",
                "ACCOUNTING",
                "JOURNAL_ENTRY_POSTED",
                "SUCCESS",
                "ops@example.com",
                "JOURNAL_ENTRY",
                "JE-17",
                1,
                25)
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expected);
    verify(auditAccessService).queryAccountingFeed(expectedFilter);
  }

  @Test
  void transactionAudit_parsesDatesAndDelegates() {
    AuditAccessService auditAccessService = mock(AuditAccessService.class);
    AccountingAuditController controller = new AccountingAuditController(auditAccessService);
    PageResponse<AccountingTransactionAuditListItemDto> expected =
        PageResponse.of(
            List.of(
                new AccountingTransactionAuditListItemDto(
                    17L,
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
                    java.math.BigDecimal.TEN,
                    java.math.BigDecimal.TEN,
                    null,
                    null,
                    null,
                    "OK",
                    Instant.parse("2026-03-05T10:00:00Z"))),
            1,
            0,
            50);
    when(auditAccessService.queryAccountingTransactions(
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            "ACCOUNTING",
            "POSTED",
            "JE-17",
            0,
            50))
        .thenReturn(expected);

    ApiResponse<PageResponse<AccountingTransactionAuditListItemDto>> body =
        controller
            .transactionAudit("2026-03-01", "2026-03-31", "ACCOUNTING", "POSTED", "JE-17", 0, 50)
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expected);
    verify(auditAccessService)
        .queryAccountingTransactions(
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            "ACCOUNTING",
            "POSTED",
            "JE-17",
            0,
            50);
  }

  @Test
  void transactionAuditDetail_delegatesToAuditAccessService() {
    AuditAccessService auditAccessService = mock(AuditAccessService.class);
    AccountingAuditController controller = new AccountingAuditController(auditAccessService);
    AccountingTransactionAuditDetailDto expected =
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
    when(auditAccessService.getAccountingTransactionDetail(17L)).thenReturn(expected);

    ApiResponse<AccountingTransactionAuditDetailDto> body =
        controller.transactionAuditDetail(17L).getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expected);
    verify(auditAccessService).getAccountingTransactionDetail(17L);
  }

  @Test
  void listEvents_usesCategoryAsModuleWhenModuleIsOmitted() {
    AuditAccessService auditAccessService = mock(AuditAccessService.class);
    AccountingAuditController controller = new AccountingAuditController(auditAccessService);
    PageResponse<AuditFeedItemDto> expected = PageResponse.of(List.of(), 0, 0, 50);
    AuditFeedFilter expectedFilter =
        new AuditFeedFilter(null, null, "INVENTORY", null, null, null, null, null, 0, 50);
    when(auditAccessService.queryAccountingFeed(expectedFilter)).thenReturn(expected);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("category", "INVENTORY");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      ApiResponse<PageResponse<AuditFeedItemDto>> body =
          controller
              .listEvents(null, null, null, null, null, null, null, null, 0, 50)
              .getBody();

      assertThat(body).isNotNull();
      assertThat(body.data()).isEqualTo(expected);
      verify(auditAccessService).queryAccountingFeed(expectedFilter);
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }
}
