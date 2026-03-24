package com.bigbrightpaints.erp.modules.inventory.controller;

import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("critical")
class FinishedGoodControllerTest {

    @Test
    void registerBatch_usesPathIdForManualBatchRegistration() {
        FinishedGoodsService finishedGoodsService = mock(FinishedGoodsService.class);
        FinishedGoodController controller = new FinishedGoodController(finishedGoodsService);
        FinishedGoodBatchRequest request = new FinishedGoodBatchRequest(
                999L,
                "FG-BATCH-001",
                new BigDecimal("4"),
                new BigDecimal("12.50"),
                Instant.parse("2026-03-01T10:15:30Z"),
                LocalDate.parse("2027-03-01"));
        FinishedGoodBatchDto response = new FinishedGoodBatchDto(
                10L,
                UUID.randomUUID(),
                "FG-BATCH-001",
                new BigDecimal("4"),
                new BigDecimal("4"),
                new BigDecimal("12.50"),
                Instant.parse("2026-03-01T10:15:30Z"),
                LocalDate.parse("2027-03-01"));
        when(finishedGoodsService.registerBatch(new FinishedGoodBatchRequest(
                123L,
                "FG-BATCH-001",
                new BigDecimal("4"),
                new BigDecimal("12.50"),
                Instant.parse("2026-03-01T10:15:30Z"),
                LocalDate.parse("2027-03-01")))).thenReturn(response);

        FinishedGoodBatchDto payload = controller.registerBatch(123L, request).getBody().data();

        assertThat(payload).isEqualTo(response);
        verify(finishedGoodsService).registerBatch(new FinishedGoodBatchRequest(
                123L,
                "FG-BATCH-001",
                new BigDecimal("4"),
                new BigDecimal("12.50"),
                Instant.parse("2026-03-01T10:15:30Z"),
                LocalDate.parse("2027-03-01")));
    }
}
