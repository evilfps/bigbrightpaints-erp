package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import com.bigbrightpaints.erp.modules.accounting.event.InventoryAccountingEventListener;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

class CR_InventoryGlAutomationDefaultOffIT extends AbstractIntegrationTest {

  @Autowired private Environment environment;

  @Autowired private ApplicationContext applicationContext;

  @Test
  @DisplayName("Shared runtime config disables inventory->GL auto-posting by default")
  void inventoryGlAutomationDisabledByDefault() {
    Boolean enabled =
        environment.getProperty("erp.inventory.accounting.events.enabled", Boolean.class);
    assertThat(enabled).isFalse();
    assertThat(applicationContext.getBeansOfType(InventoryAccountingEventListener.class)).isEmpty();
  }
}
