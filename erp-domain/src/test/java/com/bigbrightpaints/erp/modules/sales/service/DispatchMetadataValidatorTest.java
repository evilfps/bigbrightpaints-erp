package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DispatchMetadataValidatorTest {

    @Test
    void validate_acceptsDriverOnlyTransportActorWhenVehicleAndChallanArePresent() {
        DispatchConfirmRequest request = new DispatchConfirmRequest(
                11L,
                22L,
                List.of(),
                "notes",
                "tester",
                Boolean.FALSE,
                null,
                null,
                null,
                "Ayaan",
                "MH12AB1234",
                "LR-7788"
        );

        assertThat(DispatchMetadataValidator.hasRequiredMetadata(request)).isTrue();
        assertThatCode(() -> DispatchMetadataValidator.validate(request)).doesNotThrowAnyException();
    }
}
