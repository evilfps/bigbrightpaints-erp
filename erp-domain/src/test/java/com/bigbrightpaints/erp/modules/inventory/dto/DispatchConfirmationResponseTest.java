package com.bigbrightpaints.erp.modules.inventory.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
class DispatchConfirmationResponseTest {

    @Test
    void legacyConstructorLeavesArtifactMetadataOptional() {
        DispatchConfirmationResponse.LineResult line = new DispatchConfirmationResponse.LineResult(
                101L,
                "FG-101",
                "Primer",
                new BigDecimal("3.00"),
                new BigDecimal("2.00"),
                new BigDecimal("1.00"),
                new BigDecimal("50.00"),
                new BigDecimal("100.00"),
                "Packed");
        DispatchConfirmationResponse response = new DispatchConfirmationResponse(
                10L,
                "PS-10",
                "DISPATCHED",
                Instant.parse("2026-03-11T10:15:30Z"),
                "factory.user",
                new BigDecimal("150.00"),
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                11L,
                12L,
                List.of(line),
                13L);

        assertThat(response.packagingSlipId()).isEqualTo(10L);
        assertThat(response.lines()).containsExactly(line);
        assertThat(response.backorderSlipId()).isEqualTo(13L);
        assertThat(response.transporterName()).isNull();
        assertThat(response.driverName()).isNull();
        assertThat(response.vehicleNumber()).isNull();
        assertThat(response.challanReference()).isNull();
        assertThat(response.deliveryChallanNumber()).isNull();
        assertThat(response.deliveryChallanPdfPath()).isNull();
    }
}
