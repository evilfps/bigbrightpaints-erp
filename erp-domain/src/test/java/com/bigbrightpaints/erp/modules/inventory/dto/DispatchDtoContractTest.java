package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("critical")
class DispatchDtoContractTest {

    @Test
    void dispatchConfirmationRequest_legacyConstructorDefaultsLogisticsFieldsToNull() {
        DispatchConfirmationRequest request = new DispatchConfirmationRequest(
                10L,
                List.of(new DispatchConfirmationRequest.LineConfirmation(1L, BigDecimal.ONE, "ok")),
                "notes",
                "factory.user",
                50L);

        assertThat(request.transporterName()).isNull();
        assertThat(request.driverName()).isNull();
        assertThat(request.vehicleNumber()).isNull();
        assertThat(request.challanReference()).isNull();
    }

    @Test
    void dispatchConfirmationResponse_legacyConstructorDefaultsLogisticsAndChallanFieldsToNull() {
        DispatchConfirmationResponse response = new DispatchConfirmationResponse(
                10L,
                "PS-10",
                "DISPATCHED",
                Instant.parse("2026-03-09T00:00:00Z"),
                "factory.user",
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                11L,
                12L,
                List.of(new DispatchConfirmationResponse.LineResult(1L, "FG-1", "Primer", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.TEN, "ok")),
                13L);

        assertThat(response.transporterName()).isNull();
        assertThat(response.driverName()).isNull();
        assertThat(response.vehicleNumber()).isNull();
        assertThat(response.challanReference()).isNull();
        assertThat(response.deliveryChallanNumber()).isNull();
        assertThat(response.deliveryChallanPdfPath()).isNull();
    }

    @Test
    void packagingSlipDto_legacyConstructorDefaultsLogisticsAndChallanFieldsToNull() {
        PackagingSlipDto dto = new PackagingSlipDto(
                10L,
                UUID.randomUUID(),
                100L,
                "SO-100",
                "Dealer",
                "PS-10",
                "DISPATCHED",
                Instant.parse("2026-03-09T00:00:00Z"),
                Instant.parse("2026-03-09T00:00:00Z"),
                "factory.user",
                Instant.parse("2026-03-09T00:10:00Z"),
                "notes",
                21L,
                22L,
                List.of());

        assertThat(dto.transporterName()).isNull();
        assertThat(dto.driverName()).isNull();
        assertThat(dto.vehicleNumber()).isNull();
        assertThat(dto.challanReference()).isNull();
        assertThat(dto.deliveryChallanNumber()).isNull();
        assertThat(dto.deliveryChallanPdfPath()).isNull();
    }
}
