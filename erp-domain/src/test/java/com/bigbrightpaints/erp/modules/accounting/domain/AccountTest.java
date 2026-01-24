package com.bigbrightpaints.erp.modules.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AccountTest {

    @Test
    void setParent_setsHierarchyLevelToParentPlusOne() {
        Account parent = new Account();
        parent.setHierarchyLevel(2);
        Account child = new Account();

        child.setParent(parent);

        assertThat(child.getHierarchyLevel()).isEqualTo(3);
    }

    @Test
    void setParent_nullResetsHierarchyLevelToOne() {
        Account account = new Account();
        account.setHierarchyLevel(3);

        account.setParent(null);

        assertThat(account.getHierarchyLevel()).isEqualTo(1);
    }

    @Test
    void getHierarchyLevel_defaultsToOneWhenNull() {
        Account account = new Account();
        account.setHierarchyLevel(null);

        assertThat(account.getHierarchyLevel()).isEqualTo(1);
    }

    @Test
    void isLeafAccount_trueWhenHierarchyLevelNull() {
        Account account = new Account();
        account.setHierarchyLevel(null);

        assertThat(account.isLeafAccount()).isTrue();
    }

    @Test
    void isLeafAccount_trueWhenHierarchyLevelThree() {
        Account account = new Account();
        account.setHierarchyLevel(3);

        assertThat(account.isLeafAccount()).isTrue();
    }

    @Test
    void validateBalanceUpdate_throwsOnNullBalance() {
        Account account = new Account();

        assertThatThrownBy(() -> account.validateBalanceUpdate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("balance");
    }

    @Test
    void validateBalanceUpdate_allowsNegativeAssetBalance() {
        Account account = new Account();
        account.setType(AccountType.ASSET);

        assertThatCode(() -> account.validateBalanceUpdate(new BigDecimal("-1.00")))
                .doesNotThrowAnyException();
    }
}
