package com.bigbrightpaints.erp.modules.sales.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderStatusHistoryDto;
import com.bigbrightpaints.erp.modules.sales.service.DealerService;
import com.bigbrightpaints.erp.modules.sales.service.SalesDashboardService;
import com.bigbrightpaints.erp.modules.sales.service.SalesDispatchReconciliationService;
import com.bigbrightpaints.erp.modules.sales.service.SalesOrderCrudService;
import com.bigbrightpaints.erp.modules.sales.service.SalesOrderLifecycleService;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;

@ExtendWith(MockitoExtension.class)
class SalesControllerIdempotencyHeaderTest {

  @Mock private SalesService salesService;

  @Mock private DealerService dealerService;
  @Mock private SalesOrderCrudService salesOrderCrudService;
  @Mock private SalesOrderLifecycleService salesOrderLifecycleService;
  @Mock private SalesDispatchReconciliationService salesDispatchReconciliationService;
  @Mock private SalesDashboardService salesDashboardService;

  @Test
  void createOrder_appliesPrimaryHeaderIdempotencyKeyWhenBodyMissing() {
    SalesController controller = createController();
    when(salesOrderCrudService.createOrder(any())).thenReturn(null);

    controller.createOrder("hdr-001", null, requestWithoutIdempotencyKey());

    ArgumentCaptor<SalesOrderRequest> captor = ArgumentCaptor.forClass(SalesOrderRequest.class);
    verify(salesOrderCrudService).createOrder(captor.capture());
    assertThat(captor.getValue().idempotencyKey()).isEqualTo("hdr-001");
  }

  @Test
  void createOrder_rejectsLegacyHeader() {
    SalesController controller = createController();
    assertThatThrownBy(
            () -> controller.createOrder(null, "legacy-001", requestWithoutIdempotencyKey()))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getMessage())
                  .contains("X-Idempotency-Key is not supported for sales orders");
              assertThat(ex.getDetails())
                  .containsEntry("legacyHeader", "X-Idempotency-Key")
                  .containsEntry("legacyHeaderValue", "legacy-001")
                  .containsEntry("canonicalHeader", "Idempotency-Key")
                  .containsEntry("canonicalPath", "/api/v1/sales/orders");
            });
    verifyNoInteractions(salesOrderCrudService);
  }

  @Test
  void createOrder_rejectsHeaderBodyMismatch() {
    SalesController controller = createController();

    assertThatThrownBy(
            () -> controller.createOrder("hdr-001", null, requestWithIdempotencyKey("body-001")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Idempotency key mismatch");
  }

  @Test
  void createOrder_rejectsWhenPrimaryAndLegacyHeadersAreBothSent() {
    SalesController controller = createController();

    assertThatThrownBy(
            () -> controller.createOrder("hdr-001", "legacy-001", requestWithoutIdempotencyKey()))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getMessage())
                  .contains("X-Idempotency-Key is not supported for sales orders");
              assertThat(ex.getDetails())
                  .containsEntry("legacyHeader", "X-Idempotency-Key")
                  .containsEntry("legacyHeaderValue", "legacy-001")
                  .containsEntry("canonicalHeader", "Idempotency-Key")
                  .containsEntry("canonicalPath", "/api/v1/sales/orders");
            });
    verifyNoInteractions(salesOrderCrudService);
  }

  @Test
  void searchOrders_rejectsInvalidFromDate() {
    SalesController controller = createController();

    assertThatThrownBy(() -> controller.searchOrders(null, null, null, "bad-date", null, 0, 50))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("fromDate must be an ISO-8601 instant");
  }

  @Test
  void cancelOrder_combinesReasonCodeAndReasonText() {
    SalesController controller = createController();

    when(salesOrderLifecycleService.cancelOrder(44L, "CUSTOMER_REQUEST|Customer changed mind"))
        .thenReturn(
            new com.bigbrightpaints.erp.modules.sales.dto.SalesOrderDto(
                44L,
                java.util.UUID.randomUUID(),
                "SO-44",
                "CANCELLED",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "NONE",
                false,
                BigDecimal.ZERO,
                "INR",
                null,
                null,
                null,
                Instant.now(),
                List.of(),
                List.of()));

    var response =
        controller.cancelOrder(
            44L, new SalesController.CancelRequest("CUSTOMER_REQUEST", "Customer changed mind"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(salesOrderLifecycleService).cancelOrder(44L, "CUSTOMER_REQUEST|Customer changed mind");
  }

  @Test
  void orderTimeline_delegatesToLifecycleService() {
    SalesController controller = createController();

    List<SalesOrderStatusHistoryDto> timeline =
        List.of(
            new SalesOrderStatusHistoryDto(
                1L, null, "DRAFT", "ORDER_CREATED", "Order created", "alice", Instant.now()));
    when(salesOrderLifecycleService.orderTimeline(99L)).thenReturn(timeline);

    var response = controller.orderTimeline(99L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).hasSize(1);
    verify(salesOrderLifecycleService).orderTimeline(99L);
  }

  private SalesOrderRequest requestWithoutIdempotencyKey() {
    return requestWithIdempotencyKey(null);
  }

  private SalesOrderRequest requestWithIdempotencyKey(String idempotencyKey) {
    return new SalesOrderRequest(
        101L,
        new BigDecimal("100.00"),
        "INR",
        "test-order",
        List.of(
            new SalesOrderItemRequest(
                "SKU-1",
                "line",
                new BigDecimal("1.00"),
                new BigDecimal("100.00"),
                BigDecimal.ZERO)),
        "NONE",
        BigDecimal.ZERO,
        Boolean.FALSE,
        idempotencyKey,
        "CREDIT");
  }

  private SalesController createController() {
    return new SalesController(
        salesService,
        salesOrderCrudService,
        salesOrderLifecycleService,
        salesDispatchReconciliationService,
        salesDashboardService,
        dealerService);
  }
}
