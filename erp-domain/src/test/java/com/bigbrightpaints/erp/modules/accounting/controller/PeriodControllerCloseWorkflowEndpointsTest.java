package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistDto;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistItemDto;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistUpdateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestActionRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

class PeriodControllerCloseWorkflowEndpointsTest {

  @Test
  void requestPeriodClose_delegatesToService() {
    AccountingPeriodService periodService = mock(AccountingPeriodService.class);
    PeriodController controller = controller(periodService);
    PeriodCloseRequestActionRequest request =
        new PeriodCloseRequestActionRequest("  prepare close  ", true);
    PeriodCloseRequestDto expected = periodCloseRequestDto(101L, "PENDING");
    when(periodService.requestPeriodClose(77L, request)).thenReturn(expected);

    ApiResponse<PeriodCloseRequestDto> body = controller.requestPeriodClose(77L, request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.success()).isTrue();
    assertThat(body.message()).isEqualTo("Period close request submitted");
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void approvePeriodClose_delegatesToService() {
    AccountingPeriodService periodService = mock(AccountingPeriodService.class);
    PeriodController controller = controller(periodService);
    PeriodCloseRequestActionRequest request = new PeriodCloseRequestActionRequest("approved", true);
    AccountingPeriodDto expected = periodDto(77L, "CLOSED");
    when(periodService.approvePeriodClose(77L, request)).thenReturn(expected);

    ApiResponse<AccountingPeriodDto> body = controller.approvePeriodClose(77L, request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.success()).isTrue();
    assertThat(body.message()).isEqualTo("Accounting period close approved");
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void rejectPeriodClose_delegatesToService() {
    AccountingPeriodService periodService = mock(AccountingPeriodService.class);
    PeriodController controller = controller(periodService);
    PeriodCloseRequestActionRequest request =
        new PeriodCloseRequestActionRequest("needs correction", null);
    PeriodCloseRequestDto expected = periodCloseRequestDto(102L, "REJECTED");
    when(periodService.rejectPeriodClose(77L, request)).thenReturn(expected);

    ApiResponse<PeriodCloseRequestDto> body = controller.rejectPeriodClose(77L, request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.success()).isTrue();
    assertThat(body.message()).isEqualTo("Accounting period close rejected");
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void reopenPeriod_delegatesToService() {
    AccountingPeriodService periodService = mock(AccountingPeriodService.class);
    PeriodController controller = controller(periodService);
    AccountingPeriodReopenRequest request =
        new AccountingPeriodReopenRequest("historical correction");
    AccountingPeriodDto expected = periodDto(77L, "OPEN");
    when(periodService.reopenPeriod(77L, request)).thenReturn(expected);

    ApiResponse<AccountingPeriodDto> body = controller.reopenPeriod(77L, request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.success()).isTrue();
    assertThat(body.message()).isEqualTo("Accounting period reopened");
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void checklist_delegatesToService() {
    AccountingPeriodService periodService = mock(AccountingPeriodService.class);
    PeriodController controller = controller(periodService);
    MonthEndChecklistDto expected = checklistDto(77L, false, true, "pre-close notes");
    when(periodService.getMonthEndChecklist(77L)).thenReturn(expected);

    ApiResponse<MonthEndChecklistDto> body = controller.checklist(77L).getBody();

    assertThat(body).isNotNull();
    assertThat(body.success()).isTrue();
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void updateChecklist_delegatesToService() {
    AccountingPeriodService periodService = mock(AccountingPeriodService.class);
    PeriodController controller = controller(periodService);
    MonthEndChecklistUpdateRequest request =
        new MonthEndChecklistUpdateRequest(true, true, "close-ready notes");
    MonthEndChecklistDto expected = checklistDto(77L, true, true, "close-ready notes");
    when(periodService.updateMonthEndChecklist(77L, request)).thenReturn(expected);

    ApiResponse<MonthEndChecklistDto> body = controller.updateChecklist(77L, request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.success()).isTrue();
    assertThat(body.message()).isEqualTo("Checklist updated");
    assertThat(body.data()).isEqualTo(expected);
  }

  private PeriodController controller(AccountingPeriodService periodService) {
    return new PeriodController(periodService);
  }

  private AccountingPeriodDto periodDto(Long id, String status) {
    return new AccountingPeriodDto(
        id,
        2026,
        2,
        LocalDate.of(2026, 2, 1),
        LocalDate.of(2026, 2, 28),
        "February 2026",
        status,
        false,
        null,
        null,
        false,
        null,
        null,
        status.equals("CLOSED") ? Instant.parse("2026-02-28T12:00:00Z") : null,
        status.equals("CLOSED") ? "checker.user" : null,
        status.equals("CLOSED") ? "approved" : null,
        status.equals("CLOSED") ? Instant.parse("2026-02-28T12:00:00Z") : null,
        status.equals("CLOSED") ? "checker.user" : null,
        status.equals("CLOSED") ? "approved" : null,
        status.equals("OPEN") ? Instant.parse("2026-03-01T08:00:00Z") : null,
        status.equals("OPEN") ? "super.admin" : null,
        status.equals("OPEN") ? "historical correction" : null,
        status.equals("CLOSED") ? 9901L : null,
        status.equals("CLOSED") ? "approved" : null,
        "WEIGHTED_AVERAGE");
  }

  private PeriodCloseRequestDto periodCloseRequestDto(Long id, String status) {
    return new PeriodCloseRequestDto(
        id,
        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        77L,
        "February 2026",
        "OPEN",
        status,
        true,
        "maker.user",
        "prepare close",
        Instant.parse("2026-02-28T10:00:00Z"),
        status.equals("PENDING") ? null : "checker.user",
        status.equals("PENDING") ? null : Instant.parse("2026-02-28T12:00:00Z"),
        status.equals("REJECTED") ? "needs correction" : null,
        status.equals("REJECTED") ? null : "approved");
  }

  private MonthEndChecklistDto checklistDto(
      Long periodId, boolean bankReconciled, boolean inventoryCounted, String notes) {
    AccountingPeriodDto period = periodDto(periodId, "OPEN");
    period =
        new AccountingPeriodDto(
            period.id(),
            period.year(),
            period.month(),
            period.startDate(),
            period.endDate(),
            period.label(),
            period.status(),
            bankReconciled,
            bankReconciled ? Instant.parse("2026-02-28T09:00:00Z") : null,
            bankReconciled ? "accounting.user" : null,
            inventoryCounted,
            inventoryCounted ? Instant.parse("2026-02-28T09:30:00Z") : null,
            inventoryCounted ? "accounting.user" : null,
            period.closedAt(),
            period.closedBy(),
            period.closedReason(),
            period.lockedAt(),
            period.lockedBy(),
            period.lockReason(),
            period.reopenedAt(),
            period.reopenedBy(),
            period.reopenReason(),
            period.closingJournalEntryId(),
            notes,
            period.costingMethod());
    return new MonthEndChecklistDto(
        period,
        List.of(
            new MonthEndChecklistItemDto(
                "bankReconciled",
                "Bank accounts reconciled",
                bankReconciled,
                bankReconciled ? "Confirmed" : "Pending review"),
            new MonthEndChecklistItemDto(
                "inventoryCounted",
                "Inventory counted",
                inventoryCounted,
                inventoryCounted ? "Counts logged" : "Awaiting stock count")),
        bankReconciled && inventoryCounted);
  }
}
