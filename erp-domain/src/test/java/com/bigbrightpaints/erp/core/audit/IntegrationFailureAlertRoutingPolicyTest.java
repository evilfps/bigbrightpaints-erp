package com.bigbrightpaints.erp.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntegrationFailureAlertRoutingPolicyTest {

    @Test
    void resolveRoute_mapsMalformedRequestContractToSev3Ticket() {
        assertThat(IntegrationFailureAlertRoutingPolicy.resolveRoute(
                "MALFORMED_REQUEST_PAYLOAD",
                "request-parse")).isEqualTo("SEV3_TICKET");
    }

    @Test
    void resolveRoute_returnsUnmappedForUnknownCombination() {
        assertThat(IntegrationFailureAlertRoutingPolicy.resolveRoute(
                "SOME_OTHER_FAILURE",
                "request-parse")).isEqualTo("UNMAPPED");
    }

    @Test
    void resolveRoute_mapsSettlementValidationToSev3Ticket() {
        assertThat(IntegrationFailureAlertRoutingPolicy.resolveRoute(
                "SETTLEMENT_OPERATION_FAILED",
                "VALIDATION")).isEqualTo("SEV3_TICKET");
    }

    @Test
    void resolveRoute_mapsSettlementConcurrencyToSev2Urgent() {
        assertThat(IntegrationFailureAlertRoutingPolicy.resolveRoute(
                "SETTLEMENT_OPERATION_FAILED",
                "CONCURRENCY")).isEqualTo("SEV2_URGENT");
    }
}
