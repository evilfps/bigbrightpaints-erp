package com.bigbrightpaints.erp.codered;

import com.bigbrightpaints.erp.core.health.RequiredConfigHealthIndicator;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore;
import com.bigbrightpaints.erp.modules.sales.service.SalesCoreEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CR_ProductionMonitoringContractTest {

    @Test
    @DisplayName("Business metric names are stable for orders and journals")
    void businessMetricNamesAreStable() {
        String salesEngineSource = source(SalesCoreEngine.class);
        String accountingEventStoreSource = source(AccountingEventStore.class);

        assertThat(salesEngineSource)
                .contains("erp.business.orders.processed")
                .contains("erp.business.orders.processed.by_company");

        assertThat(accountingEventStoreSource)
                .contains("erp.business.journals.created")
                .contains("erp.business.journals.created.by_company");
    }

    @Test
    @DisplayName("Production profile exposes required actuator monitoring settings")
    void productionProfileExposesRequiredActuatorMonitoringSettings() {
        String prodConfig = readResource("application-prod.yml");

        assertThat(prodConfig)
                .contains("management:")
                .contains("server:")
                .contains("port: ${MANAGEMENT_SERVER_PORT:9090}")
                .contains("include: health,info,metrics,prometheus")
                .contains("include: readinessState,db,rabbit,diskSpace,requiredConfig,configuration")
                .contains("include: livenessState,ping,diskSpace")
                .contains("metrics:")
                .contains("prometheus:")
                .contains("otlp:")
                .contains("tracing:")
                .contains("endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318/v1/traces}")
                .contains("timeout-per-shutdown-phase: ${SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE:30s}")
                .contains("shutdown: graceful");
    }

    @Test
    @DisplayName("Required configuration health indicator reports UP with complete configuration")
    void requiredConfigurationHealthIndicatorReportsUp() {
        RequiredConfigHealthIndicator indicator = new RequiredConfigHealthIndicator(
                "12345678901234567890123456789012",
                "abcdefghijklmnopqrstuvwxyz123456",
                true,
                "license-key",
                true,
                "smtp-relay.example.com",
                "mailer-user",
                "secret-password"
        );

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("jwtSecretConfigured", true)
                .containsEntry("encryptionKeyConfigured", true)
                .containsEntry("mailConfigured", true)
                .containsEntry("licenseConfigured", true);
    }

    @Test
    @DisplayName("Required configuration health indicator fails closed for missing secrets")
    void requiredConfigurationHealthIndicatorFailsClosed() {
        RequiredConfigHealthIndicator indicator = new RequiredConfigHealthIndicator(
                "short",
                "tiny",
                true,
                "",
                true,
                "",
                "",
                ""
        );

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat((Iterable<String>) health.getDetails().get("missing"))
                .contains("jwt.secret", "erp.security.encryption.key", "erp.licensing.license-key", "spring.mail.host/username/password");
    }

    private String source(Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try {
            var url = type.getResource(resource);
            assertThat(url).as("class resource for %s", type.getSimpleName()).isNotNull();
            var classPath = url.toString();
            if (classPath.startsWith("file:")) {
                // During tests we compile from source tree, so class file location mirrors target/classes path.
                String srcRoot = classPath.substring(0, classPath.indexOf("/target/classes/"));
                String javaPath = srcRoot + "/src/main/java/" + type.getName().replace('.', '/') + ".java";
                return Files.readString(Path.of(new java.net.URI(javaPath)));
            }
            throw new IllegalStateException("Unsupported class resource URI: " + classPath);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to read source for " + type.getName(), ex);
        }
    }

    private String readResource(String relativePath) {
        try {
            Path classpathFile = Path.of(getClass().getResource("/" + relativePath).toURI());
            return Files.readString(classpathFile);
        } catch (Exception ignored) {
            Path fallback = Path.of("src/main/resources", relativePath);
            try {
                return Files.readString(fallback);
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to read resource " + relativePath, ex);
            }
        }
    }
}
