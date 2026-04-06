package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.accounting.service.DealerReceiptService;
import com.bigbrightpaints.erp.modules.accounting.service.SettlementService;

@ExtendWith(MockitoExtension.class)
class SettlementControllerIdempotencyHeaderParityTest {

  @Mock private DealerReceiptService dealerReceiptService;

  @Mock private SettlementService settlementService;

  @Test
  void recordDealerReceipt_usesCanonicalHeaderEvenWhenBodyKeyIsPresent() {
    SettlementController controller = controller();
    when(dealerReceiptService.recordDealerReceipt(any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        DealerReceiptRequest.class,
        () -> controller.recordDealerReceipt(dealerReceiptRequest("body-001"), "hdr-001", null),
        captor -> verify(dealerReceiptService).recordDealerReceipt(captor.capture()),
        DealerReceiptRequest::idempotencyKey,
        "hdr-001");
  }

  @Test
  void recordDealerReceipt_blankBodyKeyFallsBackToHeader() {
    SettlementController controller = controller();
    when(dealerReceiptService.recordDealerReceipt(any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        DealerReceiptRequest.class,
        () -> controller.recordDealerReceipt(dealerReceiptRequest("   "), "hdr-blank-001", null),
        captor -> verify(dealerReceiptService).recordDealerReceipt(captor.capture()),
        DealerReceiptRequest::idempotencyKey,
        "hdr-blank-001");
  }

  @Test
  void recordDealerHybridReceipt_usesCanonicalHeaderEvenWhenBodyKeyIsPresent() {
    SettlementController controller = controller();
    when(dealerReceiptService.recordDealerReceiptSplit(any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        DealerReceiptSplitRequest.class,
        () ->
            controller.recordDealerHybridReceipt(
                dealerReceiptSplitRequest("body-001"), "hdr-002", null),
        captor -> verify(dealerReceiptService).recordDealerReceiptSplit(captor.capture()),
        DealerReceiptSplitRequest::idempotencyKey,
        "hdr-002");
  }

  @Test
  void recordDealerHybridReceipt_blankBodyKeyFallsBackToHeader() {
    SettlementController controller = controller();
    when(dealerReceiptService.recordDealerReceiptSplit(any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        DealerReceiptSplitRequest.class,
        () ->
            controller.recordDealerHybridReceipt(
                dealerReceiptSplitRequest("   "), "hdr-blank-002", null),
        captor -> verify(dealerReceiptService).recordDealerReceiptSplit(captor.capture()),
        DealerReceiptSplitRequest::idempotencyKey,
        "hdr-blank-002");
  }

  @Test
  void settleSupplier_appliesPrimaryHeaderWhenBodyMissing() {
    SettlementController controller = controller();
    when(settlementService.settleSupplierInvoices(any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        PartnerSettlementRequest.class,
        () -> controller.settleSupplier(supplierSettlementRequest(null), "hdr-003", null),
        captor -> verify(settlementService).settleSupplierInvoices(captor.capture()),
        PartnerSettlementRequest::idempotencyKey,
        "hdr-003");
  }

  @Test
  void settleSupplier_usesCanonicalHeaderEvenWhenBodyKeyIsPresent() {
    SettlementController controller = controller();
    when(settlementService.settleSupplierInvoices(any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        PartnerSettlementRequest.class,
        () -> controller.settleSupplier(supplierSettlementRequest("body-001"), "hdr-004", null),
        captor -> verify(settlementService).settleSupplierInvoices(captor.capture()),
        PartnerSettlementRequest::idempotencyKey,
        "hdr-004");
  }

  @Test
  void settleSupplier_blankBodyKeyFallsBackToHeader() {
    SettlementController controller = controller();
    when(settlementService.settleSupplierInvoices(any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        PartnerSettlementRequest.class,
        () -> controller.settleSupplier(supplierSettlementRequest("   "), "hdr-blank-004", null),
        captor -> verify(settlementService).settleSupplierInvoices(captor.capture()),
        PartnerSettlementRequest::idempotencyKey,
        "hdr-blank-004");
  }

  @Test
  void settleDealer_usesCanonicalHeaderEvenWhenBodyKeyIsPresent() {
    SettlementController controller = controller();
    when(settlementService.settleDealerInvoices(any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        PartnerSettlementRequest.class,
        () -> controller.settleDealer(dealerSettlementRequest("body-001"), "hdr-005", null),
        captor -> verify(settlementService).settleDealerInvoices(captor.capture()),
        PartnerSettlementRequest::idempotencyKey,
        "hdr-005");
  }

  @Test
  void settleDealer_blankBodyKeyFallsBackToHeader() {
    SettlementController controller = controller();
    when(settlementService.settleDealerInvoices(any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        PartnerSettlementRequest.class,
        () -> controller.settleDealer(dealerSettlementRequest("   "), "hdr-blank-005", null),
        captor -> verify(settlementService).settleDealerInvoices(captor.capture()),
        PartnerSettlementRequest::idempotencyKey,
        "hdr-blank-005");
  }

  @Test
  void autoSettleDealer_appliesPrimaryHeaderWhenBodyMissing() {
    SettlementController controller = controller();
    when(settlementService.autoSettleDealer(any(), any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest.class,
        () -> controller.autoSettleDealer(1001L, autoSettlementRequest(null), "hdr-auto-001", null),
        captor -> verify(settlementService).autoSettleDealer(any(), captor.capture()),
        com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest::idempotencyKey,
        "hdr-auto-001");
  }

  @Test
  void autoSettleDealer_usesCanonicalHeaderEvenWhenBodyKeyIsPresent() {
    SettlementController controller = controller();
    when(settlementService.autoSettleDealer(any(), any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest.class,
        () ->
            controller.autoSettleDealer(
                1001L, autoSettlementRequest("body-001"), "hdr-auto-002", null),
        captor -> verify(settlementService).autoSettleDealer(any(), captor.capture()),
        com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest::idempotencyKey,
        "hdr-auto-002");
  }

  @Test
  void autoSettleSupplier_usesCanonicalHeaderEvenWhenBodyKeyIsPresent() {
    SettlementController controller = controller();
    when(settlementService.autoSettleSupplier(any(), any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest.class,
        () ->
            controller.autoSettleSupplier(
                3001L, autoSettlementRequest("body-001"), "hdr-auto-003", null),
        captor -> verify(settlementService).autoSettleSupplier(any(), captor.capture()),
        com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest::idempotencyKey,
        "hdr-auto-003");
  }

  @Test
  void settleDealer_rejectsRetiredLegacyHeader() {
    SettlementController controller = controller();

    assertThatThrownBy(
            () ->
                controller.settleDealer(
                    dealerSettlementRequest(null), null, "legacy-settlement-header"))
        .hasMessageContaining("X-Idempotency-Key is not supported for dealer settlements");
    verifyNoInteractions(settlementService);
  }

  private SettlementController controller() {
    return new SettlementController(dealerReceiptService, settlementService);
  }

  private DealerReceiptRequest dealerReceiptRequest(String idempotencyKey) {
    return new DealerReceiptRequest(
        1001L, 2001L, new BigDecimal("100.00"), "RCPT-001", "memo", idempotencyKey, allocations());
  }

  private DealerReceiptSplitRequest dealerReceiptSplitRequest(String idempotencyKey) {
    return new DealerReceiptSplitRequest(
        1001L,
        List.of(new DealerReceiptSplitRequest.IncomingLine(2001L, new BigDecimal("100.00"))),
        "RCPT-SPLIT-001",
        "memo",
        idempotencyKey);
  }

  private PartnerSettlementRequest dealerSettlementRequest(String idempotencyKey) {
    return new PartnerSettlementRequest(
        1001L,
        2001L,
        null,
        null,
        null,
        null,
        LocalDate.of(2026, 2, 15),
        "SETTLE-DEALER-001",
        "memo",
        idempotencyKey,
        Boolean.FALSE,
        allocations(),
        null);
  }

  private PartnerSettlementRequest supplierSettlementRequest(String idempotencyKey) {
    return new PartnerSettlementRequest(
        3001L,
        2001L,
        null,
        null,
        null,
        null,
        LocalDate.of(2026, 2, 15),
        "SETTLE-001",
        "memo",
        idempotencyKey,
        Boolean.FALSE,
        allocations());
  }

  private com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest
      autoSettlementRequest(String idempotencyKey) {
    return new com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest(
        2001L, new BigDecimal("50.00"), "AUTO-SETTLE-001", "memo", idempotencyKey);
  }

  private List<SettlementAllocationRequest> allocations() {
    return List.of(
        new SettlementAllocationRequest(
            4001L,
            null,
            new BigDecimal("50.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "allocation"));
  }

  private <T> void assertForwardedIdempotencyKey(
      Class<T> requestType,
      Runnable invocation,
      Consumer<ArgumentCaptor<T>> verification,
      Function<T, String> idempotencyKeyExtractor,
      String expectedIdempotencyKey) {
    invocation.run();
    ArgumentCaptor<T> captor = ArgumentCaptor.forClass(requestType);
    verification.accept(captor);
    assertThat(idempotencyKeyExtractor.apply(captor.getValue())).isEqualTo(expectedIdempotencyKey);
  }
}
