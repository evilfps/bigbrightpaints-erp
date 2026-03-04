package com.bigbrightpaints.erp.modules.inventory.controller;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryExpiringBatchDto;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialAdjustmentRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialBatchDto;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialIntakeRequest;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
    void createBatch_appliesLegacyHeaderWhenPrimaryMissing() {
        RawMaterialController controller = controller();
        RawMaterialBatchRequest request = batchRequest();
        when(rawMaterialService.createBatch(42L, request, "legacy-key")).thenReturn(batchDto());

        controller.createBatch(42L, null, "legacy-key", request);

        verify(rawMaterialService).createBatch(42L, request, "legacy-key");
    }

    @Test
    void createBatch_rejectsWhenPrimaryLegacyHeadersMismatch() {
        RawMaterialController controller = controller();
        RawMaterialBatchRequest request = batchRequest();

        assertThatThrownBy(() -> controller.createBatch(42L, "primary-key", "legacy-key", request))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
                    assertThat(ex.getMessage()).isEqualTo(
                            "Idempotency key mismatch between Idempotency-Key and X-Idempotency-Key headers");
                    assertThat(ex.getDetails())
                            .containsEntry("idempotencyKeyHeader", "primary-key")
                            .containsEntry("legacyIdempotencyKeyHeader", "legacy-key");
                });
        verifyNoInteractions(rawMaterialService);
    }

    @Test
    void intake_appliesLegacyHeaderWhenPrimaryMissing() {
        RawMaterialController controller = controller();
        RawMaterialIntakeRequest request = intakeRequest();
        when(rawMaterialService.intake(request, "legacy-key")).thenReturn(batchDto());

        controller.intake(null, "legacy-key", request);

        verify(rawMaterialService).intake(request, "legacy-key");
    }

    @Test
    void intake_rejectsWhenPrimaryLegacyHeadersMismatch() {
        RawMaterialController controller = controller();
        RawMaterialIntakeRequest request = intakeRequest();

        assertThatThrownBy(() -> controller.intake("primary-key", "legacy-key", request))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
                    assertThat(ex.getMessage()).isEqualTo(
                            "Idempotency key mismatch between Idempotency-Key and X-Idempotency-Key headers");
                    assertThat(ex.getDetails())
                            .containsEntry("idempotencyKeyHeader", "primary-key")
                            .containsEntry("legacyIdempotencyKeyHeader", "legacy-key");
                });
        verifyNoInteractions(rawMaterialService);
    }

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

    private RawMaterialBatchRequest batchRequest() {
        return new RawMaterialBatchRequest(
                "BATCH-1",
                new BigDecimal("10.00"),
                "KG",
                new BigDecimal("250.00"),
                7L,
                null,
                null,
                "test"
        );
    }

    private RawMaterialIntakeRequest intakeRequest() {
        return new RawMaterialIntakeRequest(
                11L,
                "BATCH-1",
                new BigDecimal("10.00"),
                "KG",
                new BigDecimal("250.00"),
                7L,
                null,
                null,
                "test"
        );
    }

    private RawMaterialBatchDto batchDto() {
        return new RawMaterialBatchDto(
                1L,
                UUID.fromString("23af8db6-c2ec-4607-850f-2f85e8a6579f"),
                "BATCH-1",
                new BigDecimal("10.00"),
                "KG",
                new BigDecimal("250.00"),
                7L,
                "Supplier",
                Instant.parse("2026-02-15T00:00:00Z"),
                "test"
        );
    }
}
