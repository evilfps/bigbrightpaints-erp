package com.bigbrightpaints.erp;

import com.bigbrightpaints.erp.core.config.EmailProperties;
import com.bigbrightpaints.erp.core.security.JwtProperties;
import com.bigbrightpaints.erp.core.config.LicensingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableRetry
@EnableScheduling
@EnableConfigurationProperties({JwtProperties.class, EmailProperties.class, LicensingProperties.class})
public class ErpDomainApplication {

    public static void main(String[] args) {
        SpringApplication.run(ErpDomainApplication.class, args);
    }
}
