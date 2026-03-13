package com.bigbrightpaints.erp.modules.inventory.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
class DispatchConfirmationRequestTest {

    @Test
    void legacyConstructorLeavesDispatchMetadataOptional() {
        DispatchConfirmationRequest.LineConfirmation line =
                new DispatchConfirmationRequest.LineConfirmation(100L, new BigDecimal("2.50"), "Ship");
        DispatchConfirmationRequest request =
                new DispatchConfirmationRequest(10L, List.of(line), "notes", "factory.user", 99L);

        assertThat(request.packagingSlipId()).isEqualTo(10L);
        assertThat(request.lines()).containsExactly(line);
        assertThat(request.notes()).isEqualTo("notes");
        assertThat(request.confirmedBy()).isEqualTo("factory.user");
        assertThat(request.overrideRequestId()).isEqualTo(99L);
        assertThat(request.transporterName()).isNull();
        assertThat(request.driverName()).isNull();
        assertThat(request.vehicleNumber()).isNull();
        assertThat(request.challanReference()).isNull();
    }
}
