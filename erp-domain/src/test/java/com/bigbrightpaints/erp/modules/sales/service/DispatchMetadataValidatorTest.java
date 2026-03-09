package com.bigbrightpaints.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
class DispatchMetadataValidatorTest {

    @Test
    void validate_acceptsTransporterMetadata() {
        assertThatCode(() -> DispatchMetadataValidator.validate(validRequest("Carrier", null)))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_acceptsDriverOnlyMetadata() {
        assertThatCode(() -> DispatchMetadataValidator.validate(validRequest(null, "Driver One")))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsMissingTransportActor() {
        assertThatThrownBy(() -> DispatchMetadataValidator.validate(validRequest(" ", " ")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("transporterName or driverName");
    }

    @Test
    void validate_rejectsMissingVehicleAndChallan() {
        DispatchConfirmRequest missingVehicle = new DispatchConfirmRequest(
                10L,
                20L,
                List.of(new DispatchConfirmRequest.DispatchLine(1L, 2L, BigDecimal.ONE, null, null, null, null, null)),
                "notes",
                "tester",
                Boolean.FALSE,
                null,
                null,
                "Carrier",
                null,
                " ",
                "CH-20"
        );
        DispatchConfirmRequest missingChallan = new DispatchConfirmRequest(
                10L,
                20L,
                List.of(new DispatchConfirmRequest.DispatchLine(1L, 2L, BigDecimal.ONE, null, null, null, null, null)),
                "notes",
                "tester",
                Boolean.FALSE,
                null,
                null,
                "Carrier",
                null,
                "MH12AB1234",
                " "
        );

        assertThatThrownBy(() -> DispatchMetadataValidator.validate(missingVehicle))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("vehicleNumber");
        assertThatThrownBy(() -> DispatchMetadataValidator.validate(missingChallan))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("challanReference");
    }

    private DispatchConfirmRequest validRequest(String transporterName, String driverName) {
        return new DispatchConfirmRequest(
                10L,
                20L,
                List.of(new DispatchConfirmRequest.DispatchLine(1L, 2L, BigDecimal.ONE, null, null, null, null, null)),
                "notes",
                "tester",
                Boolean.FALSE,
                null,
                null,
                transporterName,
                driverName,
                "MH12AB1234",
                "CH-20"
        );
    }
}
