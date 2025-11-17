package com.bigbrightpaints.erp.test;

import com.bigbrightpaints.erp.core.config.EmailProperties;
import com.bigbrightpaints.erp.core.security.JwtProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableConfigurationProperties({JwtProperties.class, EmailProperties.class})
@ComponentScan(basePackages = {
        "com.bigbrightpaints.erp.core",
        "com.bigbrightpaints.erp.modules",
        "com.bigbrightpaints.erp.orchestrator",
        "com.bigbrightpaints.erp.shared",
        "com.bigbrightpaints.erp.test"
})
@EnableJpaRepositories(basePackages = "com.bigbrightpaints.erp")
@EntityScan(basePackages = "com.bigbrightpaints.erp")
public class TestApplication {
}
