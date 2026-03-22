package com.bigbrightpaints.erp.modules.inventory.controller;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryExpiringBatchDto;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialAdjustmentRequest;
import com.bigbrightpaints.erp.modules.inventory.service.InventoryBatchQueryService;
import com.bigbrightpaints.erp.modules.inventory.service.RawMaterialService;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RawMaterialControllerTest {

    @Mock
    private RawMaterialService rawMaterialService;

    @Mock
    private InventoryBatchQueryService inventoryBatchQueryService;

    @Test
    void adjustRawMaterials_appliesHeaderIdempotencyWhenBodyMissing() {
        RawMaterialController controller = controller();
        RawMaterialAdjustmentRequest request = adjustmentRequest(null);

        controller.adjustRawMaterials("header-key", null, request);

        verify(rawMaterialService).adjustStock(eq(new RawMaterialAdjustmentRequest(
                request.adjustmentDate(),
                request.direction(),
                request.adjustmentAccountId(),
                request.reason(),
                request.adminOverride(),
                "header-key",
                request.lines()
        )));
    }

    @Test
    void adjustRawMaterials_rejectsWhenHeaderBodyMismatch() {
        RawMaterialController controller = controller();

        RawMaterialAdjustmentRequest request = adjustmentRequest("body-key");
        assertThatThrownBy(() -> controller.adjustRawMaterials("header-key", null, request))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
                    assertThat(ex.getMessage()).isEqualTo("Idempotency key mismatch between header and request body");
                });

        verifyNoInteractions(rawMaterialService);
    }

    @Test
    void adjustRawMaterials_rejectsWhenIdempotencyMissingInBodyAndHeader() {
        RawMaterialController controller = controller();

        RawMaterialAdjustmentRequest request = adjustmentRequest(null);
        assertThatThrownBy(() -> controller.adjustRawMaterials(null, null, request))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD);
                    assertThat(ex.getMessage()).isEqualTo("Idempotency-Key header is required");
                });

        verifyNoInteractions(rawMaterialService);
    }

    @Test
    void adjustRawMaterials_validatesResolvedRequest() {
        RawMaterialController controller = controller();

        RawMaterialAdjustmentRequest invalid = new RawMaterialAdjustmentRequest(
                LocalDate.of(2026, 3, 4),
                null,
                null,
                "reason",
                Boolean.FALSE,
                null,
                List.of(new RawMaterialAdjustmentRequest.LineRequest(null, null, null, "note"))
        );

        assertThatThrownBy(() -> controller.adjustRawMaterials("header-key", null, invalid))
                .isInstanceOf(ConstraintViolationException.class);

        verifyNoInteractions(rawMaterialService);
    }

    @Test
    void expiringSoonBatches_clampsNegativeDaysToZero() {
        RawMaterialController controller = controller();

        List<InventoryExpiringBatchDto> result = List.of();
        when(inventoryBatchQueryService.listExpiringSoonBatches(0)).thenReturn(result);

        controller.expiringSoonBatches(-5);

        verify(inventoryBatchQueryService).listExpiringSoonBatches(0);
    }

    private RawMaterialController controller() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        return new RawMaterialController(rawMaterialService, inventoryBatchQueryService, validator);
    }

    private RawMaterialAdjustmentRequest adjustmentRequest(String idempotencyKey) {
        return new RawMaterialAdjustmentRequest(
                LocalDate.of(2026, 3, 4),
                RawMaterialAdjustmentRequest.AdjustmentDirection.INCREASE,
                77L,
                "recount",
                Boolean.FALSE,
                idempotencyKey,
                List.of(new RawMaterialAdjustmentRequest.LineRequest(
                        11L,
                        new BigDecimal("3.00"),
                        new BigDecimal("120.00"),
                        "count"
                ))
        );
    }

}
