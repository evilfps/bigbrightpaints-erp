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

    @Value("${erp.mail.enabled:true}")
    private boolean mailEnabled = true;

    @Value("${spring.mail.host:}")
    private String smtpHost;

    @Value("${spring.mail.password:}")
    private String smtpPassword;

    @Value("${spring.mail.username:}")
    private String smtpUser;

    @Value("${spring.mail.properties.mail.smtp.auth:true}")
    private boolean smtpAuthEnabled = true;

    @PostConstruct
    void validateSmtp() {
        if (!mailEnabled) {
            log.info("SMTP validation skipped because erp.mail.enabled=false");
            return;
        }
        if (!StringUtils.hasText(smtpHost)) {
            throw new IllegalStateException("SMTP host is missing (spring.mail.host)");
        }
        if (!smtpAuthEnabled) {
            log.info("SMTP credential validation skipped because spring.mail.properties.mail.smtp.auth=false");
            return;
        }
        if (!StringUtils.hasText(smtpPassword)) {
            throw new IllegalStateException("SMTP password is missing (spring.mail.password)");
        }
        if ("changeme".equalsIgnoreCase(smtpPassword)) {
            throw new IllegalStateException("SMTP password uses default 'changeme'; set a real credential for production");
        }
        if (!StringUtils.hasText(smtpUser)) {
            throw new IllegalStateException("SMTP username is missing (spring.mail.username)");
        }
    }
}
