package com.bigbrightpaints.erp.truthsuite.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.bigbrightpaints.erp.modules.accounting.controller.SettlementController;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;

@Tag("critical")
@Tag("reconciliation")
@Tag("concurrency")
class TS_AccountingIdempotencyHeaderParityRuntimeCoverageTest {

  @Test
  void recordDealerReceipt_noBodyAndNoHeaders_preservesOriginalRequest() {
    AccountingFacade accountingFacade = mock(AccountingFacade.class);
    SettlementController controller = newController(accountingFacade);
    DealerReceiptRequest request = dealerReceiptRequest(null);
    when(accountingFacade.recordDealerReceipt(any())).thenReturn(null);

    controller.recordDealerReceipt(request, null, null);

    ArgumentCaptor<DealerReceiptRequest> captor =
        ArgumentCaptor.forClass(DealerReceiptRequest.class);
    verify(accountingFacade).recordDealerReceipt(captor.capture());
    assertThat(captor.getValue()).isSameAs(request);
    assertThat(captor.getValue().idempotencyKey()).isNull();
  }

  @Test
  void recordDealerReceipt_appliesHeaderKeyWhenBodyMissing() {
    AccountingFacade accountingFacade = mock(AccountingFacade.class);
    SettlementController controller = newController(accountingFacade);
    DealerReceiptRequest request = dealerReceiptRequest(null);
    when(accountingFacade.recordDealerReceipt(any())).thenReturn(null);

    controller.recordDealerReceipt(request, "hdr-001", null);

    ArgumentCaptor<DealerReceiptRequest> captor =
        ArgumentCaptor.forClass(DealerReceiptRequest.class);
    verify(accountingFacade).recordDealerReceipt(captor.capture());
    assertThat(captor.getValue()).isNotSameAs(request);
    assertThat(captor.getValue().idempotencyKey()).isEqualTo("hdr-001");
  }

  @Test
  void recordDealerReceipt_bodyKeyWithoutCanonicalHeader_isPreserved() {
    AccountingFacade accountingFacade = mock(AccountingFacade.class);
    SettlementController controller = newController(accountingFacade);
    DealerReceiptRequest request = dealerReceiptRequest("body-001");
    when(accountingFacade.recordDealerReceipt(any())).thenReturn(null);

    controller.recordDealerReceipt(request, null, null);

    ArgumentCaptor<DealerReceiptRequest> captor =
        ArgumentCaptor.forClass(DealerReceiptRequest.class);
    verify(accountingFacade).recordDealerReceipt(captor.capture());
    assertThat(captor.getValue()).isSameAs(request);
    assertThat(captor.getValue().idempotencyKey()).isEqualTo("body-001");
  }

  @Test
  void recordDealerReceipt_trimsNonBlankHeaders_andTreatsBlankHeadersAsMissing() {
    AccountingFacade accountingFacade = mock(AccountingFacade.class);
    SettlementController controller = newController(accountingFacade);
    DealerReceiptRequest nonBlankHeaderRequest = dealerReceiptRequest(null);
    DealerReceiptRequest blankHeaderRequest = dealerReceiptRequest(null);
    when(accountingFacade.recordDealerReceipt(any())).thenReturn(null);

    controller.recordDealerReceipt(nonBlankHeaderRequest, "  hdr-trim-001  ", null);
    controller.recordDealerReceipt(blankHeaderRequest, "   ", null);

    ArgumentCaptor<DealerReceiptRequest> captor =
        ArgumentCaptor.forClass(DealerReceiptRequest.class);
    verify(accountingFacade, org.mockito.Mockito.times(2)).recordDealerReceipt(captor.capture());
    List<DealerReceiptRequest> captured = captor.getAllValues();

    assertThat(captured.get(0)).isNotSameAs(nonBlankHeaderRequest);
    assertThat(captured.get(0).idempotencyKey()).isEqualTo("hdr-trim-001");
    assertThat(captured.get(1)).isSameAs(blankHeaderRequest);
    assertThat(captured.get(1).idempotencyKey()).isNull();
  }

  @Test
  void recordDealerReceipt_rejectsLegacyHeader() {
    AccountingFacade accountingFacade = mock(AccountingFacade.class);
    SettlementController controller = newController(accountingFacade);

    assertThatThrownBy(
            () -> controller.recordDealerReceipt(dealerReceiptRequest(null), null, "legacy-001"))
        .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
        .hasMessageContaining("X-Idempotency-Key");
    verifyNoInteractions(accountingFacade);
  }

  private SettlementController newController(AccountingFacade accountingFacade) {
    return new SettlementController(accountingFacade);
  }

  private DealerReceiptRequest dealerReceiptRequest(String idempotencyKey) {
    return new DealerReceiptRequest(
        1001L, 2001L, new BigDecimal("100.00"), "RCPT-001", "memo", idempotencyKey, allocations());
  }

  private List<SettlementAllocationRequest> allocations() {
    return List.of(
        new SettlementAllocationRequest(
            4001L,
            null,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "allocation"));
  }
}
