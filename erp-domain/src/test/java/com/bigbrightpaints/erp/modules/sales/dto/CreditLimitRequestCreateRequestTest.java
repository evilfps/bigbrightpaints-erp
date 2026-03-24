package com.bigbrightpaints.erp.modules.sales.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
class CreditLimitRequestCreateRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void accessorsAndJsonCreatorPreservePayload() throws Exception {
        CreditLimitRequestCreateRequest request = objectMapper.readValue(
                """
                {"dealerId":17,"amountRequested":1500.50,"reason":"Need durable increase"}
                """,
                CreditLimitRequestCreateRequest.class
        );

        assertThat(request.dealerId()).isEqualTo(17L);
        assertThat(request.getDealerId()).isEqualTo(17L);
        assertThat(request.amountRequested()).isEqualByComparingTo("1500.50");
        assertThat(request.getAmountRequested()).isEqualByComparingTo("1500.50");
        assertThat(request.reason()).isEqualTo("Need durable increase");
        assertThat(request.getReason()).isEqualTo("Need durable increase");
        assertThat(request.toString()).contains("dealerId=17", "amountRequested=1500.50", "reason=Need durable increase");
    }

    @Test
    void equalsAndHashCodeFollowValueSemantics() {
        CreditLimitRequestCreateRequest request = new CreditLimitRequestCreateRequest(
                17L,
                new BigDecimal("1500.50"),
                "Need durable increase"
        );
        CreditLimitRequestCreateRequest same = new CreditLimitRequestCreateRequest(
                17L,
                new BigDecimal("1500.50"),
                "Need durable increase"
        );
        CreditLimitRequestCreateRequest different = new CreditLimitRequestCreateRequest(
                18L,
                new BigDecimal("800.00"),
                "Different"
        );
        CreditLimitRequestCreateRequest differentAmount = new CreditLimitRequestCreateRequest(
                17L,
                new BigDecimal("800.00"),
                "Need durable increase"
        );
        CreditLimitRequestCreateRequest differentReason = new CreditLimitRequestCreateRequest(
                17L,
                new BigDecimal("1500.50"),
                "Different"
        );

        assertThat(request).isEqualTo(request);
        assertThat(request).isEqualTo(same);
        assertThat(request.hashCode()).isEqualTo(same.hashCode());
        assertThat(request).isNotEqualTo(different);
        assertThat(request).isNotEqualTo(differentAmount);
        assertThat(request).isNotEqualTo(differentReason);
        assertThat(request).isNotEqualTo("other");
    }

    @Test
    void beanValidation_requiresDealerIdAndAmountRequested() {
        CreditLimitRequestCreateRequest request = new CreditLimitRequestCreateRequest(
                null,
                null,
                "Need durable increase"
        );

        Map<String, String> violations = validator.validate(request).stream()
                .collect(java.util.stream.Collectors.toMap(
                        violation -> violation.getPropertyPath().toString(),
                        violation -> violation.getMessage(),
                        (left, right) -> left,
                        LinkedHashMap::new));

        assertThat(violations)
                .containsEntry("dealerId", "must not be null")
                .containsEntry("amountRequested", "must not be null");
    }
}
