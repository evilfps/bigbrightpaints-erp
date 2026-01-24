package com.bigbrightpaints.erp.modules.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccountTypeTest {

    @Test
    void isDebitNormalBalance_trueForAsset() {
        assertThat(AccountType.ASSET.isDebitNormalBalance()).isTrue();
    }

    @Test
    void isDebitNormalBalance_falseForLiability() {
        assertThat(AccountType.LIABILITY.isDebitNormalBalance()).isFalse();
    }

    @Test
    void affectsNetIncome_trueForRevenue() {
        assertThat(AccountType.REVENUE.affectsNetIncome()).isTrue();
    }

    @Test
    void affectsNetIncome_falseForAsset() {
        assertThat(AccountType.ASSET.affectsNetIncome()).isFalse();
    }

    @Test
    void isCreditNormalBalance_trueForEquity() {
        assertThat(AccountType.EQUITY.isCreditNormalBalance()).isTrue();
    }
}
