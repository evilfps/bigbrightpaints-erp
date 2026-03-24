package com.bigbrightpaints.erp.modules.sales.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
class CreditLimitRequestDecisionRequestTest {

    @Test
    void canonicalConstructor_exposesReasonAccessor() {
        CreditLimitRequestDecisionRequest request = new CreditLimitRequestDecisionRequest("Risk cleared by finance");

        assertThat(request.reason()).isEqualTo("Risk cleared by finance");
    }

    @Test
    void equalsAndHashCode_sameReason_areEqual() {
        CreditLimitRequestDecisionRequest one = new CreditLimitRequestDecisionRequest("Approved");
        CreditLimitRequestDecisionRequest two = new CreditLimitRequestDecisionRequest("Approved");

        assertThat(one).isEqualTo(two);
        assertThat(one.hashCode()).isEqualTo(two.hashCode());
    }

    @Test
    void equalsAndHashCode_differentReason_areNotEqual() {
        CreditLimitRequestDecisionRequest approved = new CreditLimitRequestDecisionRequest("Approved");
        CreditLimitRequestDecisionRequest rejected = new CreditLimitRequestDecisionRequest("Rejected");

        assertThat(approved).isNotEqualTo(rejected);
        assertThat(approved.hashCode()).isNotEqualTo(rejected.hashCode());
    }

    @Test
    void toString_includesRecordComponentValue() {
        CreditLimitRequestDecisionRequest request = new CreditLimitRequestDecisionRequest("Manual override");

        assertThat(request.toString()).contains("reason=Manual override");
    }
}
