package com.bigbrightpaints.erp.test;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyModule;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.context.annotation.Import(TestBeansConfig.class)
public abstract class AbstractIntegrationTest {

    private static final String EXTERNAL_DB_URL = resolveConfig("erp.test.db.url", "ERP_TEST_DB_URL");
    private static final String EXTERNAL_DB_USERNAME = resolveConfig("erp.test.db.username", "ERP_TEST_DB_USERNAME");
    private static final String EXTERNAL_DB_PASSWORD = resolveConfig("erp.test.db.password", "ERP_TEST_DB_PASSWORD");
    private static final boolean USE_EXTERNAL_DB = EXTERNAL_DB_URL != null && !EXTERNAL_DB_URL.isBlank();

    public static final PostgreSQLContainer<?> POSTGRES = USE_EXTERNAL_DB
            ? null
            : new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("erp_domain_test")
            .withUsername("erp_test")
            .withPassword("erp_test");

    static {
        if (!USE_EXTERNAL_DB) {
            // Ensure the container is started before Spring resolves dynamic properties.
            POSTGRES.start();
        }
    }

    @Autowired
    protected TestDataSeeder dataSeeder;

    @Autowired
    protected CompanyRepository companyRepository;

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        if (USE_EXTERNAL_DB) {
            registry.add("spring.datasource.url", () -> EXTERNAL_DB_URL);
            registry.add("spring.datasource.username", () -> defaultString(EXTERNAL_DB_USERNAME, "erp_test"));
            registry.add("spring.datasource.password", () -> defaultString(EXTERNAL_DB_PASSWORD, "erp_test"));
        } else {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        }
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration_v2");
        registry.add("spring.flyway.table", () -> "flyway_schema_history_v2");
        registry.add("spring.jpa.open-in-view", () -> true);
        // Disable AMQP/Rabbit auto-config in tests to avoid external dependency
        registry.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.quartz.QuartzAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration");
    }

    private static String resolveConfig(String propertyName, String envName) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }
        String envValue = System.getenv(envName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return null;
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    protected Company enableModule(String companyCode, CompanyModule module) {
        Company company = companyRepository.findByCodeIgnoreCase(companyCode)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + companyCode));
        return enableModule(company, module);
    }

    protected Company enableModule(Company company, CompanyModule module) {
        Company managedCompany = company;
        if (company.getId() != null) {
            managedCompany = companyRepository.findById(company.getId()).orElse(company);
        } else if (company.getCode() != null && !company.getCode().isBlank()) {
            managedCompany = companyRepository.findByCodeIgnoreCase(company.getCode()).orElse(company);
        }
        Set<String> enabledModules = new LinkedHashSet<>(managedCompany.getEnabledModules());
        enabledModules.add(module.name());
        managedCompany.setEnabledModules(enabledModules);
        return companyRepository.save(managedCompany);
    }
}
