package com.bigbrightpaints.erp.modules.inventory.controller;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialBatchDto;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialIntakeRequest;
import com.bigbrightpaints.erp.modules.inventory.service.RawMaterialService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RawMaterialControllerTest {

    @Mock
    private RawMaterialService rawMaterialService;

    @Test
    void createBatch_appliesLegacyHeaderWhenPrimaryMissing() {
        RawMaterialController controller = new RawMaterialController(rawMaterialService);
        RawMaterialBatchRequest request = batchRequest();
        when(rawMaterialService.createBatch(42L, request, "legacy-key")).thenReturn(batchDto());

        controller.createBatch(42L, null, "legacy-key", request);

        verify(rawMaterialService).createBatch(42L, request, "legacy-key");
    }

    @Test
    void createBatch_rejectsWhenPrimaryLegacyHeadersMismatch() {
        RawMaterialController controller = new RawMaterialController(rawMaterialService);
        RawMaterialBatchRequest request = batchRequest();

        assertThatThrownBy(() -> controller.createBatch(42L, "primary-key", "legacy-key", request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch between Idempotency-Key and X-Idempotency-Key headers");
        verifyNoInteractions(rawMaterialService);
    }

    @Test
    void intake_appliesLegacyHeaderWhenPrimaryMissing() {
        RawMaterialController controller = new RawMaterialController(rawMaterialService);
        RawMaterialIntakeRequest request = intakeRequest();
        when(rawMaterialService.intake(request, "legacy-key")).thenReturn(batchDto());

        controller.intake(null, "legacy-key", request);

        verify(rawMaterialService).intake(request, "legacy-key");
    }

    @Test
    void intake_rejectsWhenPrimaryLegacyHeadersMismatch() {
        RawMaterialController controller = new RawMaterialController(rawMaterialService);
        RawMaterialIntakeRequest request = intakeRequest();

        assertThatThrownBy(() -> controller.intake("primary-key", "legacy-key", request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch between Idempotency-Key and X-Idempotency-Key headers");
        verifyNoInteractions(rawMaterialService);
    }

    private RawMaterialBatchRequest batchRequest() {
        return new RawMaterialBatchRequest(
                "BATCH-1",
                new BigDecimal("10.00"),
                "KG",
                new BigDecimal("250.00"),
                7L,
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
