package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.DealerReceiptService;
import com.bigbrightpaints.erp.modules.accounting.service.SettlementService;

@ExtendWith(MockitoExtension.class)
class AccountingControllerIdempotencyHeaderParityTest {

  @Mock private AccountingService accountingService;

  @Mock private DealerReceiptService dealerReceiptService;

  @Mock private SettlementService settlementService;

  @Test
  void recordDealerReceipt_rejectsLegacyHeader() {
    AccountingController controller = controller();
    assertThatThrownBy(
            () -> controller.recordDealerReceipt(dealerReceiptRequest(null), null, "legacy-001"))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> assertLegacyHeaderContract(ex, "legacy-001", "/api/v1/accounting/receipts/dealer"));
  }

  @Test
  void recordDealerReceipt_acceptsMatchingBodyIdempotencyKeyWhenHeaderMatches() {
    AccountingController controller = controller();
    when(dealerReceiptService.recordDealerReceipt(any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        DealerReceiptRequest.class,
        () -> controller.recordDealerReceipt(dealerReceiptRequest("body-001"), "body-001", null),
        captor -> verify(dealerReceiptService).recordDealerReceipt(captor.capture()),
        DealerReceiptRequest::idempotencyKey,
        "body-001");
  }

  @Test
  void recordDealerReceipt_blankBodyKeyFallsBackToHeader() {
    AccountingController controller = controller();
    when(dealerReceiptService.recordDealerReceipt(any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        DealerReceiptRequest.class,
        () ->
            controller.recordDealerReceipt(dealerReceiptRequest("   "), "hdr-blank-001", null),
        captor -> verify(dealerReceiptService).recordDealerReceipt(captor.capture()),
        DealerReceiptRequest::idempotencyKey,
        "hdr-blank-001");
  }

  @Test
  void recordDealerHybridReceipt_rejectsLegacyHeader() {
    AccountingController controller = controller();
    assertThatThrownBy(
            () ->
                controller.recordDealerHybridReceipt(
                    dealerReceiptSplitRequest(null), null, "legacy-001"))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex ->
                assertLegacyHeaderContract(
                    ex, "legacy-001", "/api/v1/accounting/receipts/dealer/hybrid"));
  }

  @Test
  void recordDealerHybridReceipt_acceptsMatchingBodyIdempotencyKeyWhenHeaderMatches() {
    AccountingController controller = controller();
    when(dealerReceiptService.recordDealerReceiptSplit(any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        DealerReceiptSplitRequest.class,
        () ->
            controller.recordDealerHybridReceipt(
                dealerReceiptSplitRequest("body-001"), "body-001", null),
        captor -> verify(dealerReceiptService).recordDealerReceiptSplit(captor.capture()),
        DealerReceiptSplitRequest::idempotencyKey,
        "body-001");
  }

  @Test
  void recordDealerHybridReceipt_blankBodyKeyFallsBackToHeader() {
    AccountingController controller = controller();
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
    AccountingController controller = controller();
    when(settlementService.settleSupplierInvoices(any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        SupplierSettlementRequest.class,
        () -> controller.settleSupplier(supplierSettlementRequest(null), "hdr-001", null),
        captor -> verify(settlementService).settleSupplierInvoices(captor.capture()),
        SupplierSettlementRequest::idempotencyKey,
        "hdr-001");
  }

  @Test
  void settleSupplier_acceptsMatchingBodyIdempotencyKeyWhenHeaderMatches() {
    AccountingController controller = controller();
    when(settlementService.settleSupplierInvoices(any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        SupplierSettlementRequest.class,
        () -> controller.settleSupplier(supplierSettlementRequest("body-001"), "body-001", null),
        captor -> verify(settlementService).settleSupplierInvoices(captor.capture()),
        SupplierSettlementRequest::idempotencyKey,
        "body-001");
  }

  @Test
  void settleSupplier_blankBodyKeyFallsBackToHeader() {
    AccountingController controller = controller();
    when(settlementService.settleSupplierInvoices(any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        SupplierSettlementRequest.class,
        () -> controller.settleSupplier(supplierSettlementRequest("   "), "hdr-blank-004", null),
        captor -> verify(settlementService).settleSupplierInvoices(captor.capture()),
        SupplierSettlementRequest::idempotencyKey,
        "hdr-blank-004");
  }

  @Test
  void settleSupplier_rejectsLegacyHeader() {
    AccountingController controller = controller();
    assertThatThrownBy(
            () -> controller.settleSupplier(supplierSettlementRequest(null), null, "legacy-001"))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex ->
                assertLegacyHeaderContract(
                    ex, "legacy-001", "/api/v1/accounting/settlements/suppliers"));
  }

  @Test
  void settleDealer_rejectsLegacyHeader() {
    AccountingController controller = controller();
    assertThatThrownBy(
            () -> controller.settleDealer(dealerSettlementRequest(null), null, "legacy-001"))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex ->
                assertLegacyHeaderContract(
                    ex, "legacy-001", "/api/v1/accounting/settlements/dealers"));
  }

  @Test
  void settleDealer_acceptsMatchingBodyIdempotencyKeyWhenHeaderMatches() {
    AccountingController controller = controller();
    when(settlementService.settleDealerInvoices(any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        DealerSettlementRequest.class,
        () -> controller.settleDealer(dealerSettlementRequest("body-001"), "body-001", null),
        captor -> verify(settlementService).settleDealerInvoices(captor.capture()),
        DealerSettlementRequest::idempotencyKey,
        "body-001");
  }

  @Test
  void settleDealer_blankBodyKeyFallsBackToHeader() {
    AccountingController controller = controller();
    when(settlementService.settleDealerInvoices(any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        DealerSettlementRequest.class,
        () -> controller.settleDealer(dealerSettlementRequest("   "), "hdr-blank-005", null),
        captor -> verify(settlementService).settleDealerInvoices(captor.capture()),
        DealerSettlementRequest::idempotencyKey,
        "hdr-blank-005");
  }

  @Test
  void autoSettleDealer_rejectsLegacyHeader() {
    AccountingController controller = controller();
    assertThatThrownBy(
            () ->
                controller.autoSettleDealer(1001L, autoSettlementRequest(null), null, "legacy-001"))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex ->
                assertLegacyHeaderContract(
                    ex, "legacy-001", "/api/v1/accounting/dealers/{dealerId}/auto-settle"));
  }

  @Test
  void autoSettleDealer_appliesPrimaryHeaderWhenBodyMissing() {
    AccountingController controller = controller();
    when(settlementService.autoSettleDealer(any(), any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest.class,
        () -> controller.autoSettleDealer(1001L, autoSettlementRequest(null), "hdr-auto-001", null),
        captor -> verify(settlementService).autoSettleDealer(any(), captor.capture()),
        com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest::idempotencyKey,
        "hdr-auto-001");
  }

  @Test
  void autoSettleDealer_acceptsMatchingBodyIdempotencyKeyWhenHeaderMatches() {
    AccountingController controller = controller();
    when(settlementService.autoSettleDealer(any(), any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest.class,
        () -> controller.autoSettleDealer(1001L, autoSettlementRequest("body-001"), "body-001", null),
        captor -> verify(settlementService).autoSettleDealer(any(), captor.capture()),
        com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest::idempotencyKey,
        "body-001");
  }

  @Test
  void autoSettleSupplier_rejectsLegacyHeader() {
    AccountingController controller = controller();
    assertThatThrownBy(
            () ->
                controller.autoSettleSupplier(
                    3001L, autoSettlementRequest(null), null, "legacy-001"))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex ->
                assertLegacyHeaderContract(
                    ex, "legacy-001", "/api/v1/accounting/suppliers/{supplierId}/auto-settle"));
  }

  @Test
  void autoSettleSupplier_acceptsMatchingBodyIdempotencyKeyWhenHeaderMatches() {
    AccountingController controller = controller();
    when(settlementService.autoSettleSupplier(any(), any())).thenReturn(null);

    assertForwardedIdempotencyKey(
        com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest.class,
        () ->
            controller.autoSettleSupplier(
                3001L, autoSettlementRequest("body-001"), "body-001", null),
        captor -> verify(settlementService).autoSettleSupplier(any(), captor.capture()),
        com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest::idempotencyKey,
        "body-001");
  }

  private AccountingController controller() {
    return new AccountingController(
        accountingService,
        null,
        dealerReceiptService,
        settlementService,
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
        null,
        null,
        null,
        null,
        null,
        null);
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

  private DealerSettlementRequest dealerSettlementRequest(String idempotencyKey) {
    return new DealerSettlementRequest(
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

  private SupplierSettlementRequest supplierSettlementRequest(String idempotencyKey) {
    return new SupplierSettlementRequest(
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

  private void assertLegacyHeaderContract(
      ApplicationException exception, String legacyHeaderValue, String canonicalPath) {
    assertThat(exception.getMessage()).contains("X-Idempotency-Key is not supported");
    assertThat(exception.getDetails())
        .containsEntry("legacyHeader", "X-Idempotency-Key")
        .containsEntry("legacyHeaderValue", legacyHeaderValue)
        .containsEntry("canonicalHeader", "Idempotency-Key")
        .containsEntry("canonicalPath", canonicalPath);
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
