package com.bigbrightpaints.erp.core.security;

import com.bigbrightpaints.erp.core.config.LicensingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Profile("!test")
public class LicensingGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LicensingGuard.class);

    private final LicensingProperties properties;

    public LicensingGuard(LicensingProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnforce()) {
            log.info("CryptoLens licensing enforcement disabled (erp.licensing.enforce=false). Set ERP_LICENSE_ENFORCE=true in secured environments.");
            return;
        }

        if (properties.getProductId() <= 0) {
            throw new IllegalStateException("CryptoLens product id must be configured (ERP_LICENSE_PRODUCT_ID).");
        }
        if (!properties.hasLicenseKey()) {
            throw new IllegalStateException("CryptoLens license key missing. Set ERP_LICENSE_KEY before starting the service.");
        }

        log.info("CryptoLens license configured for product {} ({}), algorithm {}, created {}.",
            properties.getProductId(), properties.getDescription(), properties.getAlgorithm(), properties.getCreated());
        log.info("License key supplied ({} characters); masked={}", properties.getLicenseKey().length(), mask(properties.getLicenseKey()));

        if (!StringUtils.hasText(properties.getAccessToken())) {
            log.warn("No CryptoLens access token configured (ERP_LICENSE_ACCESS_TOKEN). Remote activation checks are skipped.");
        }
    }

    private String mask(String value) {
        if (!StringUtils.hasText(value) || value.length() < 6) {
            return "****";
        }
        return value.substring(0, 3) + "****" + value.substring(value.length() - 2);
    }
}
