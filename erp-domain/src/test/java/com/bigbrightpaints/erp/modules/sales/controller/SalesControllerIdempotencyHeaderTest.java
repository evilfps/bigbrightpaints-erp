package com.bigbrightpaints.erp.modules.sales.controller;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.service.DealerService;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesControllerIdempotencyHeaderTest {

    @Mock
    private SalesService salesService;

    @Mock
    private DealerService dealerService;

    @Test
    void createOrder_appliesPrimaryHeaderIdempotencyKeyWhenBodyMissing() {
        SalesController controller = new SalesController(salesService, dealerService);
        when(salesService.createOrder(any())).thenReturn(null);

        controller.createOrder("hdr-001", null, requestWithoutIdempotencyKey());

        ArgumentCaptor<SalesOrderRequest> captor = ArgumentCaptor.forClass(SalesOrderRequest.class);
        verify(salesService).createOrder(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("hdr-001");
    }

    @Test
    void createOrder_appliesLegacyHeaderIdempotencyKeyWhenPrimaryMissing() {
        SalesController controller = new SalesController(salesService, dealerService);
        when(salesService.createOrder(any())).thenReturn(null);

        controller.createOrder(null, "legacy-001", requestWithoutIdempotencyKey());

        ArgumentCaptor<SalesOrderRequest> captor = ArgumentCaptor.forClass(SalesOrderRequest.class);
        verify(salesService).createOrder(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("legacy-001");
    }

    @Test
    void createOrder_rejectsHeaderBodyMismatch() {
        SalesController controller = new SalesController(salesService, dealerService);

        assertThatThrownBy(() -> controller.createOrder("hdr-001", null, requestWithIdempotencyKey("body-001")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch");
    }

    @Test
    void createOrder_rejectsWhenPrimaryLegacyHeadersMismatch() {
        SalesController controller = new SalesController(salesService, dealerService);

        assertThatThrownBy(() -> controller.createOrder("hdr-001", "legacy-001", requestWithoutIdempotencyKey()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch between Idempotency-Key and X-Idempotency-Key headers");
        verifyNoInteractions(salesService);
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
                List.of(new SalesOrderItemRequest(
                        "SKU-1",
                        "line",
                        new BigDecimal("1.00"),
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO
                )),
                "NONE",
                BigDecimal.ZERO,
                Boolean.FALSE,
                idempotencyKey,
                "CREDIT"
        );
    }
}
