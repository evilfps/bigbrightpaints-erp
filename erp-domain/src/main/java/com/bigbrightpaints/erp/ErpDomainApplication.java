package com.bigbrightpaints.erp;

import com.bigbrightpaints.erp.core.security.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(JwtProperties.class)
public class ErpDomainApplication {

    public static void main(String[] args) {
        SpringApplication.run(ErpDomainApplication.class, args);
    }
}
