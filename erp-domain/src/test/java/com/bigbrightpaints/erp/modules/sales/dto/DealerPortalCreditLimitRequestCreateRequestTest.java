package com.bigbrightpaints.erp.modules.sales.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
class DealerPortalCreditLimitRequestCreateRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void accessorsAndJsonCreatorPreservePayload() throws Exception {
        DealerPortalCreditLimitRequestCreateRequest request = objectMapper.readValue(
                """
                {"amountRequested":900.25,"reason":"Portal durable request"}
                """,
                DealerPortalCreditLimitRequestCreateRequest.class
        );

        assertThat(request.amountRequested()).isEqualByComparingTo("900.25");
        assertThat(request.getAmountRequested()).isEqualByComparingTo("900.25");
        assertThat(request.reason()).isEqualTo("Portal durable request");
        assertThat(request.getReason()).isEqualTo("Portal durable request");
        assertThat(request.toString()).contains("amountRequested=900.25", "reason=Portal durable request");
    }

    @Test
    void equalsAndHashCodeFollowValueSemantics() {
        DealerPortalCreditLimitRequestCreateRequest request =
                new DealerPortalCreditLimitRequestCreateRequest(new BigDecimal("900.25"), "Portal durable request");
        DealerPortalCreditLimitRequestCreateRequest same =
                new DealerPortalCreditLimitRequestCreateRequest(new BigDecimal("900.25"), "Portal durable request");
        DealerPortalCreditLimitRequestCreateRequest different =
                new DealerPortalCreditLimitRequestCreateRequest(new BigDecimal("500.00"), "Different");
        DealerPortalCreditLimitRequestCreateRequest differentReason =
                new DealerPortalCreditLimitRequestCreateRequest(new BigDecimal("900.25"), "Different");

        assertThat(request).isEqualTo(request);
        assertThat(request).isEqualTo(same);
        assertThat(request.hashCode()).isEqualTo(same.hashCode());
        assertThat(request).isNotEqualTo(different);
        assertThat(request).isNotEqualTo(differentReason);
        assertThat(request).isNotEqualTo("other");
    }
}
