package com.bigbrightpaints.erp.modules.sales.config;

import com.bigbrightpaints.erp.core.health.ConfigurationHealthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class SalesAccountConfigurationValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SalesAccountConfigurationValidator.class);

    private final ConfigurationHealthService configurationHealthService;
    private final boolean validationEnabled;

    public SalesAccountConfigurationValidator(ConfigurationHealthService configurationHealthService,
                                              @Value("${erp.environment.validation.enabled:false}") boolean validationEnabled) {
        this.configurationHealthService = configurationHealthService;
        this.validationEnabled = validationEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!validationEnabled) {
            log.info("Environment validation disabled; skipping configuration health checks");
            return;
        }
        configurationHealthService.assertHealthy();
        log.info("Configuration health checks completed successfully");
    }
}
