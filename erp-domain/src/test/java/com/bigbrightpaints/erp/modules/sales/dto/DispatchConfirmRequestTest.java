package com.bigbrightpaints.erp.modules.sales.dto;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DispatchConfirmRequestTest {

    @Test
    void legacyConstructor_defaultsLogisticsFieldsToNull() {
        DispatchConfirmRequest request = new DispatchConfirmRequest(
                10L,
                100L,
                List.of(new DispatchConfirmRequest.DispatchLine(1L, 2L, BigDecimal.ONE, null, null, null, null, null)),
                "notes",
                "admin",
                Boolean.FALSE,
                null,
                50L);

        assertThat(request.transporterName()).isNull();
        assertThat(request.driverName()).isNull();
        assertThat(request.vehicleNumber()).isNull();
        assertThat(request.challanReference()).isNull();
    }
}
