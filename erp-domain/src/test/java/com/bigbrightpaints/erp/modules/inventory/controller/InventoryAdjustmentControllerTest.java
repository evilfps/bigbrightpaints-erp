package com.bigbrightpaints.erp.modules.inventory.controller;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryAdjustmentType;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentRequest;
import com.bigbrightpaints.erp.modules.inventory.service.InventoryAdjustmentService;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryAdjustmentControllerTest {

    @Mock
    private InventoryAdjustmentService inventoryAdjustmentService;

    @Test
    void createAdjustment_rejectsWhenIdempotencyMissing() {
        InventoryAdjustmentController controller = controller();
        InventoryAdjustmentRequest request = validRequest(null);

        assertThatThrownBy(() -> controller.createAdjustment(null, null, request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency-Key header is required");
    }

    @Test
    void createAdjustment_rejectsHeaderBodyMismatch() {
        InventoryAdjustmentController controller = controller();
        InventoryAdjustmentRequest request = validRequest("body-key");

        assertThatThrownBy(() -> controller.createAdjustment("header-key", null, request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch");
    }

    @Test
    void createAdjustment_appliesHeaderIdempotencyKeyWhenBodyMissing() {
        InventoryAdjustmentController controller = controller();
        when(inventoryAdjustmentService.createAdjustment(any())).thenReturn(null);

        InventoryAdjustmentRequest request = validRequest(null);
        controller.createAdjustment("header-key", null, request);

        ArgumentCaptor<InventoryAdjustmentRequest> captor = ArgumentCaptor.forClass(InventoryAdjustmentRequest.class);
        verify(inventoryAdjustmentService).createAdjustment(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("header-key");
    }

    @Test
    void createAdjustment_appliesLegacyHeaderIdempotencyKeyWhenPrimaryMissing() {
        InventoryAdjustmentController controller = controller();
        when(inventoryAdjustmentService.createAdjustment(any())).thenReturn(null);

        InventoryAdjustmentRequest request = validRequest(null);
        controller.createAdjustment(null, "legacy-key", request);

        ArgumentCaptor<InventoryAdjustmentRequest> captor = ArgumentCaptor.forClass(InventoryAdjustmentRequest.class);
        verify(inventoryAdjustmentService).createAdjustment(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("legacy-key");
    }

    @Test
    void createAdjustment_rejectsWhenPrimaryLegacyHeadersMismatch() {
        InventoryAdjustmentController controller = controller();
        InventoryAdjustmentRequest request = validRequest(null);

        assertThatThrownBy(() -> controller.createAdjustment("header-key", "legacy-key", request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch between Idempotency-Key and X-Idempotency-Key headers");
        verifyNoInteractions(inventoryAdjustmentService);
    }

    @Test
    void createAdjustment_validatesResolvedRequest() {
        InventoryAdjustmentController controller = controller();
        InventoryAdjustmentRequest invalid = new InventoryAdjustmentRequest(
                LocalDate.of(2026, 2, 9),
                null,
                null,
                "reason",
                Boolean.FALSE,
                null,
                List.of(new InventoryAdjustmentRequest.LineRequest(null, null, null, "note"))
        );

        assertThatThrownBy(() -> controller.createAdjustment("header-key", null, invalid))
                .isInstanceOf(ConstraintViolationException.class);
    }

    private InventoryAdjustmentController controller() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        return new InventoryAdjustmentController(inventoryAdjustmentService, validator);
    }

    private InventoryAdjustmentRequest validRequest(String idempotencyKey) {
        return new InventoryAdjustmentRequest(
                LocalDate.of(2026, 2, 9),
                InventoryAdjustmentType.DAMAGED,
                101L,
                "reason",
                Boolean.FALSE,
                idempotencyKey,
                List.of(new InventoryAdjustmentRequest.LineRequest(
                        1001L,
                        new BigDecimal("1.00"),
                        new BigDecimal("10.00"),
                        "Damaged"
                ))
        );
    }
}
