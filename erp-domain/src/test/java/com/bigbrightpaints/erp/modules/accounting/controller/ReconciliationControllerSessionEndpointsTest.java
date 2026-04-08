package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionCompletionRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionCreateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionDetailDto;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionItemsUpdateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionSummaryDto;
import com.bigbrightpaints.erp.modules.accounting.service.BankReconciliationSessionService;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

class ReconciliationControllerSessionEndpointsTest {

  @Test
  void legacyBankEndpoint_isUnmapped() throws Exception {
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                controller(
                    mock(ReconciliationService.class),
                    mock(BankReconciliationSessionService.class)))
            .build();

    mvc.perform(post("/api/v1/accounting/reconciliation/bank")).andExpect(status().isNotFound());
  }

  @Test
  void startBankReconciliationSession_delegates() {
    BankReconciliationSessionService sessionService = mock(BankReconciliationSessionService.class);
    ReconciliationController controller =
        controller(mock(ReconciliationService.class), sessionService);
    BankReconciliationSessionCreateRequest request =
        new BankReconciliationSessionCreateRequest(
            8L,
            LocalDate.of(2026, 3, 31),
            new BigDecimal("1200.50"),
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            5L,
            "March bank rec");
    BankReconciliationSessionSummaryDto expected = sessionSummary(17L);
    when(sessionService.startSession(request)).thenReturn(expected);

    var response = controller.startBankReconciliationSession(request);
    ApiResponse<BankReconciliationSessionSummaryDto> body = response.getBody();

    assertThat(body).isNotNull();
    assertThat(body.message()).isEqualTo("Bank reconciliation session started");
    assertThat(body.data()).isEqualTo(expected);
    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(sessionService).startSession(request);
  }

  @Test
  void updateBankReconciliationSessionItems_delegates() {
    BankReconciliationSessionService sessionService = mock(BankReconciliationSessionService.class);
    ReconciliationController controller =
        controller(mock(ReconciliationService.class), sessionService);
    BankReconciliationSessionItemsUpdateRequest request =
        new BankReconciliationSessionItemsUpdateRequest(List.of(11L, 12L), List.of(13L), "matched");
    BankReconciliationSessionDetailDto expected = sessionDetail(21L);
    when(sessionService.updateItems(21L, request)).thenReturn(expected);

    ApiResponse<BankReconciliationSessionDetailDto> body =
        controller.updateBankReconciliationSessionItems(21L, request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.message()).isEqualTo("Bank reconciliation session updated");
    assertThat(body.data()).isEqualTo(expected);
    verify(sessionService).updateItems(21L, request);
  }

  @Test
  void updateBankReconciliationSessionItems_duplicateBankItemValidationReturnsBadRequest() throws Exception {
    BankReconciliationSessionService sessionService = mock(BankReconciliationSessionService.class);
    when(sessionService.updateItems(eq(21L), any(BankReconciliationSessionItemsUpdateRequest.class)))
        .thenThrow(
            new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT,
                "Duplicate bankItemId assignment is not allowed: bankItemId 9001 is assigned to journalLineId 11 and 12"));

    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                controller(mock(ReconciliationService.class), sessionService))
            .setControllerAdvice(new AccountingApplicationExceptionAdvice())
            .build();

    mvc.perform(
            put("/api/v1/accounting/reconciliation/bank/sessions/21/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "addJournalLineIds":[11,12],
                      "removeJournalLineIds":[],
                      "note":"duplicate bank item",
                      "matches":[
                        {"bankItemId":9001,"journalEntryId":null,"journalLineId":11},
                        {"bankItemId":9001,"journalEntryId":null,"journalLineId":12}
                      ]
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.message").value("Duplicate bankItemId assignment is not allowed: bankItemId 9001 is assigned to journalLineId 11 and 12"))
        .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_INVALID_INPUT.getCode()))
        .andExpect(jsonPath("$.data.reason").value("Duplicate bankItemId assignment is not allowed: bankItemId 9001 is assigned to journalLineId 11 and 12"));
  }

  @Test
  void completeBankReconciliationSession_delegates() {
    BankReconciliationSessionService sessionService = mock(BankReconciliationSessionService.class);
    ReconciliationController controller =
        controller(mock(ReconciliationService.class), sessionService);
    BankReconciliationSessionCompletionRequest request =
        new BankReconciliationSessionCompletionRequest("ready to close", 9L);
    BankReconciliationSessionDetailDto expected = sessionDetail(22L);
    when(sessionService.completeSession(22L, request)).thenReturn(expected);

    ApiResponse<BankReconciliationSessionDetailDto> body =
        controller.completeBankReconciliationSession(22L, request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.message()).isEqualTo("Bank reconciliation session completed");
    assertThat(body.data()).isEqualTo(expected);
    verify(sessionService).completeSession(22L, request);
  }

  @Test
  void listAndGetBankReconciliationSessions_delegate() {
    BankReconciliationSessionService sessionService = mock(BankReconciliationSessionService.class);
    ReconciliationController controller =
        controller(mock(ReconciliationService.class), sessionService);
    BankReconciliationSessionSummaryDto summary = sessionSummary(30L);
    BankReconciliationSessionDetailDto detail = sessionDetail(30L);
    PageResponse<BankReconciliationSessionSummaryDto> page =
        PageResponse.of(List.of(summary), 1, 0, 20);
    when(sessionService.listSessions(0, 20)).thenReturn(page);
    when(sessionService.getSessionDetail(30L)).thenReturn(detail);

    ApiResponse<PageResponse<BankReconciliationSessionSummaryDto>> pageBody =
        controller.listBankReconciliationSessions(0, 20).getBody();
    ApiResponse<BankReconciliationSessionDetailDto> detailBody =
        controller.getBankReconciliationSession(30L).getBody();

    assertThat(pageBody).isNotNull();
    assertThat(pageBody.data()).isEqualTo(page);
    assertThat(detailBody).isNotNull();
    assertThat(detailBody.data()).isEqualTo(detail);
    verify(sessionService).listSessions(0, 20);
    verify(sessionService).getSessionDetail(30L);
  }

  @Test
  void subledgerAndInterCompanyReports_delegate() {
    ReconciliationService reconciliationService = mock(ReconciliationService.class);
    ReconciliationController controller =
        controller(reconciliationService, mock(BankReconciliationSessionService.class));

    controller.reconcileSubledger();
    controller.reconcileInterCompany(3L, 4L);

    verify(reconciliationService).reconcileSubledgerBalances();
    verify(reconciliationService).interCompanyReconcile(3L, 4L);
  }

  @Test
  void interCompanyReport_allowsMissingQueryParams() {
    ReconciliationService reconciliationService = mock(ReconciliationService.class);
    ReconciliationController controller =
        controller(reconciliationService, mock(BankReconciliationSessionService.class));

    controller.reconcileInterCompany(null, null);

    verify(reconciliationService).interCompanyReconcile(null, null);
  }

  private ReconciliationController controller(
      ReconciliationService reconciliationService,
      BankReconciliationSessionService bankReconciliationSessionService) {
    return new ReconciliationController(reconciliationService, bankReconciliationSessionService);
  }

  private BankReconciliationSessionSummaryDto sessionSummary(Long sessionId) {
    return new BankReconciliationSessionSummaryDto(
        sessionId,
        "BRS-" + sessionId,
        8L,
        "BANK-001",
        "Main Bank",
        LocalDate.of(2026, 3, 31),
        new BigDecimal("1200.50"),
        "IN_PROGRESS",
        "tester",
        Instant.parse("2026-03-31T10:00:00Z"),
        null,
        null,
        0);
  }

  private BankReconciliationSessionDetailDto sessionDetail(Long sessionId) {
    return new BankReconciliationSessionDetailDto(
        sessionId,
        "BRS-" + sessionId,
        8L,
        "BANK-001",
        "Main Bank",
        LocalDate.of(2026, 3, 31),
        new BigDecimal("1200.50"),
        "IN_PROGRESS",
        9L,
        "March bank rec",
        "tester",
        Instant.parse("2026-03-31T10:00:00Z"),
        null,
        null,
        List.of(),
        List.of(),
        null);
  }
}
