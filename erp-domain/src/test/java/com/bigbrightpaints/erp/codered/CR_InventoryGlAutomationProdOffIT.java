package com.bigbrightpaints.erp.codered;

import com.bigbrightpaints.erp.modules.accounting.event.InventoryAccountingEventListener;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles(value = {"test", "prod"}, inheritProfiles = false)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-should-be-at-least-32-bytes-long-1234",
        "spring.mail.password=test-smtp-password",
        "ERP_LICENSE_KEY=test-license-key",
        "ERP_DISPATCH_DEBIT_ACCOUNT_ID=1",
        "ERP_DISPATCH_CREDIT_ACCOUNT_ID=2",
        "management.endpoint.health.validate-group-membership=false",
        "management.server.port=0",
        "erp.environment.validation.enabled=false"
})
class CR_InventoryGlAutomationProdOffIT extends AbstractIntegrationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("Prod disables inventory->GL auto-posting unless outbox-backed")
    void inventoryGlAutomationDisabledInProd() {
        Boolean enabled = environment.getProperty("erp.inventory.accounting.events.enabled", Boolean.class, true);
        assertThat(enabled).isFalse();
        assertThat(applicationContext.getBeansOfType(InventoryAccountingEventListener.class)).isEmpty();
    }
}
