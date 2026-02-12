package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AccountingEventTrailAlertRoutingPolicyTest {

    @ParameterizedTest
    @CsvSource({
            "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE,DATA_INTEGRITY,STRICT,SEV1_PAGE",
            "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE,DATA_INTEGRITY,BEST_EFFORT,SEV1_PAGE",
            "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE,VALIDATION,STRICT,SEV2_URGENT",
            "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE,VALIDATION,BEST_EFFORT,SEV2_URGENT",
            "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE,PERSISTENCE,STRICT,SEV2_URGENT",
            "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE,PERSISTENCE,BEST_EFFORT,SEV3_TICKET"
    })
    void resolveRoute_mapsKnownFailureCategoryPolicyCombinations(String failureCode,
                                                                 String errorCategory,
                                                                 String policy,
                                                                 String expectedRoute) {
        assertThat(AccountingEventTrailAlertRoutingPolicy.resolveRoute(failureCode, errorCategory, policy))
                .isEqualTo(expectedRoute);
    }

    @Test
    void resolveRoute_unknownCategoryFallsBackToSev3Ticket() {
        assertThat(AccountingEventTrailAlertRoutingPolicy.resolveRoute(
                AccountingEventTrailAlertRoutingPolicy.ACCOUNTING_EVENT_TRAIL_FAILURE_CODE,
                "SOMETHING_NEW",
                "STRICT")).isEqualTo(AccountingEventTrailAlertRoutingPolicy.ROUTE_SEV3_TICKET);
    }

    @Test
    void resolveRoute_nonContractFailureCodeIsUnmapped() {
        assertThat(AccountingEventTrailAlertRoutingPolicy.resolveRoute(
                "ANOTHER_FAILURE_CODE",
                "DATA_INTEGRITY",
                "STRICT")).isEqualTo(AccountingEventTrailAlertRoutingPolicy.ROUTE_UNMAPPED);
    }
}
