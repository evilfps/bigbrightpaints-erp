package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.bigbrightpaints.erp.modules.accounting.event.InventoryAccountingEventListener;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@ActiveProfiles(
    value = {"test", "prod"},
    inheritProfiles = false)
@TestPropertySource(
    properties = {
      "jwt.secret=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
      "spring.mail.host=localhost",
      "spring.mail.username=test-smtp-user",
      "spring.mail.password=test-smtp-password",
      "ERP_SECURITY_AUDIT_PRIVATE_KEY=test-audit-private-key",
      "ERP_SECURITY_ENCRYPTION_KEY=12345678901234567890123456789012",
      "ERP_LICENSE_KEY=test-license-key",
      "ERP_DISPATCH_DEBIT_ACCOUNT_ID=1",
      "ERP_DISPATCH_CREDIT_ACCOUNT_ID=2",
      "management.endpoint.health.validate-group-membership=false",
      "management.server.port=0",
      "erp.environment.validation.enabled=false",
      "management.endpoints.web.exposure.include=health,info,metrics,prometheus",
      "management.endpoint.metrics.access=read-only",
      "management.endpoint.prometheus.access=read-only"
    })
class CR_InventoryGlAutomationProdOffIT extends AbstractIntegrationTest {

  @Autowired private Environment environment;

  @Autowired private ApplicationContext applicationContext;

  @Test
  @DisplayName("Prod disables inventory->GL auto-posting unless outbox-backed")
  void inventoryGlAutomationDisabledInProd() {
    Boolean enabled =
        environment.getProperty("erp.inventory.accounting.events.enabled", Boolean.class);
    assertThat(enabled).isFalse();
    assertThat(applicationContext.getBeansOfType(InventoryAccountingEventListener.class)).isEmpty();
  }
}
