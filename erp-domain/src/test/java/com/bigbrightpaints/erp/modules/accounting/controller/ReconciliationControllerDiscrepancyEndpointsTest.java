package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyResolution;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyType;
import com.bigbrightpaints.erp.modules.accounting.dto.ReconciliationDiscrepancyDto;
import com.bigbrightpaints.erp.modules.accounting.dto.ReconciliationDiscrepancyListResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.ReconciliationDiscrepancyResolveRequest;
import com.bigbrightpaints.erp.modules.accounting.service.BankReconciliationSessionService;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

class ReconciliationControllerDiscrepancyEndpointsTest {

  @Test
  void listDiscrepancies_parsesFiltersAndDelegates() {
    ReconciliationService reconciliationService = mock(ReconciliationService.class);
    ReconciliationController controller = controller(reconciliationService);

    ReconciliationDiscrepancyDto item = discrepancyDto(11L, "OPEN", "AR", null, null);
    ReconciliationDiscrepancyListResponse response =
        new ReconciliationDiscrepancyListResponse(List.of(item), 1, 0);
    when(reconciliationService.listDiscrepancies(
            ReconciliationDiscrepancyStatus.OPEN, ReconciliationDiscrepancyType.AR))
        .thenReturn(response);

    ApiResponse<ReconciliationDiscrepancyListResponse> body =
        controller.listReconciliationDiscrepancies("open", "ar").getBody();

    assertThat(body).isNotNull();
    assertThat(body.success()).isTrue();
    assertThat(body.data()).isEqualTo(response);
    verify(reconciliationService)
        .listDiscrepancies(ReconciliationDiscrepancyStatus.OPEN, ReconciliationDiscrepancyType.AR);
  }

  @Test
  void listDiscrepancies_withoutFiltersPassesNulls() {
    ReconciliationService reconciliationService = mock(ReconciliationService.class);
    ReconciliationController controller = controller(reconciliationService);

    ReconciliationDiscrepancyListResponse response =
        new ReconciliationDiscrepancyListResponse(List.of(), 0, 0);
    when(reconciliationService.listDiscrepancies(null, null)).thenReturn(response);

    ApiResponse<ReconciliationDiscrepancyListResponse> body =
        controller.listReconciliationDiscrepancies(null, null).getBody();

    assertThat(body).isNotNull();
    assertThat(body.success()).isTrue();
    assertThat(body.data().items()).isEmpty();
    verify(reconciliationService).listDiscrepancies(null, null);
  }

  @Test
  void listDiscrepancies_rejectsInvalidStatus() {
    ReconciliationController controller = controller(mock(ReconciliationService.class));

    assertThatThrownBy(() -> controller.listReconciliationDiscrepancies("UNKNOWN", "AR"))
        .isInstanceOf(ApplicationException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
  }

  @Test
  void listDiscrepancies_rejectsInvalidType() {
    ReconciliationController controller = controller(mock(ReconciliationService.class));

    assertThatThrownBy(() -> controller.listReconciliationDiscrepancies("OPEN", "BANK"))
        .isInstanceOf(ApplicationException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
  }

  @Test
  void resolveDiscrepancy_delegatesAndWrapsResponse() {
    ReconciliationService reconciliationService = mock(ReconciliationService.class);
    ReconciliationController controller = controller(reconciliationService);

    ReconciliationDiscrepancyResolveRequest request =
        new ReconciliationDiscrepancyResolveRequest(
            ReconciliationDiscrepancyResolution.ACKNOWLEDGED, "reviewed", null);
    ReconciliationDiscrepancyDto resolved =
        discrepancyDto(51L, "ACKNOWLEDGED", "AR", "ACKNOWLEDGED", null);
    when(reconciliationService.resolveDiscrepancy(51L, request)).thenReturn(resolved);

    ApiResponse<ReconciliationDiscrepancyDto> body =
        controller.resolveReconciliationDiscrepancy(51L, request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.success()).isTrue();
    assertThat(body.message()).isEqualTo("Reconciliation discrepancy resolved");
    assertThat(body.data()).isEqualTo(resolved);
    verify(reconciliationService).resolveDiscrepancy(51L, request);
  }

  private ReconciliationController controller(ReconciliationService reconciliationService) {
    return new ReconciliationController(
        reconciliationService, mock(BankReconciliationSessionService.class));
  }

  private ReconciliationDiscrepancyDto discrepancyDto(
      Long id, String status, String type, String resolution, Long resolutionJournalId) {
    return new ReconciliationDiscrepancyDto(
        id,
        5L,
        LocalDate.of(2026, 3, 1),
        LocalDate.of(2026, 3, 31),
        type,
        "DEALER",
        77L,
        "D-77",
        "Dealer 77",
        new BigDecimal("100.00"),
        new BigDecimal("80.00"),
        new BigDecimal("20.00"),
        status,
        resolution,
        "note",
        resolutionJournalId,
        "SYSTEM_PROCESS",
        Instant.parse("2026-03-20T10:15:30Z"),
        Instant.parse("2026-03-20T10:00:00Z"),
        Instant.parse("2026-03-20T10:15:30Z"));
  }
}
