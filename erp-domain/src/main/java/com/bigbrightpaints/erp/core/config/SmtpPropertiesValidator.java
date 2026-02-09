package com.bigbrightpaints.erp.core.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

@Configuration
@Profile("prod & !seed")
public class SmtpPropertiesValidator {

    private static final Logger log = LoggerFactory.getLogger(SmtpPropertiesValidator.class);

    @Value("${spring.mail.password:}")
    private String smtpPassword;

    @Value("${spring.mail.username:}")
    private String smtpUser;

    @PostConstruct
    void validateSmtp() {
        if (!StringUtils.hasText(smtpPassword)) {
            throw new IllegalStateException("SMTP password is missing (spring.mail.password)");
        }
        if ("changeme".equalsIgnoreCase(smtpPassword)) {
            throw new IllegalStateException("SMTP password uses default 'changeme'; set a real credential for production");
        }
        if (!StringUtils.hasText(smtpUser)) {
            log.warn("SMTP username is empty; verify spring.mail.username is set");
        }
    }
}
