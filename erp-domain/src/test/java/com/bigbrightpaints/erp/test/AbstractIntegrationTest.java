package com.bigbrightpaints.erp.test;

import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.context.annotation.Import(TestBeansConfig.class)
public abstract class AbstractIntegrationTest {

    @Container
    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("erp_domain_test")
            .withUsername("erp_test")
            .withPassword("erp_test");

    static {
        // Ensure the container is started before Spring resolves dynamic properties
        POSTGRES.start();
    }

    @Autowired
    protected TestDataSeeder dataSeeder;

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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
}
