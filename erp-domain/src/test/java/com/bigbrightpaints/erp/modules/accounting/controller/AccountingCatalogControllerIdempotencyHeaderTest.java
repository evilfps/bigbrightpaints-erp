package com.bigbrightpaints.erp.modules.accounting.controller;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

class AccountingCatalogControllerIdempotencyHeaderTest {

    @Test
    void retiredAccountingCatalogController_isNotInstantiableOrPublic() {
        int modifiers = AccountingCatalogController.class.getModifiers();

        assertThat(Modifier.isFinal(modifiers)).isTrue();
        assertThat(Modifier.isPublic(modifiers)).isFalse();

        Constructor<?>[] constructors = AccountingCatalogController.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();
    }

    @Test
    void retiredAccountingCatalogController_exposesNoPublicMethods() {
        assertThat(AccountingCatalogController.class.getDeclaredMethods()).isEmpty();
    }
}
