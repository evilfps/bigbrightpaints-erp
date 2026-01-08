package com.bigbrightpaints.erp.core.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates critical environment configuration for production deployments.
 * This component only runs in the 'prod' profile and ensures that all required
 * security-sensitive configurations are properly set before the application starts.
 */
@Component
@Profile("prod")
public class ProductionEnvironmentValidator {

    private static final Logger logger = LoggerFactory.getLogger(ProductionEnvironmentValidator.class);
    private static final int MIN_SECRET_LENGTH = 32;
    private static final int MIN_UNIQUE_CHARS = 8;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${erp.security.encryption.key:}")
    private String encryptionKey;

    @Value("${spring.datasource.password:}")
    private String databasePassword;

    @Value("${spring.mail.password:}")
    private String mailPassword;

    @Value("${erp.cors.allowed-origins:}")
    private String corsAllowedOrigins;

    @PostConstruct
    public void validateProductionEnvironment() {
        logger.info("Validating production environment configuration...");

        List<String> missingConfigs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Critical security configurations (must fail startup if missing)
        validateSecret("JWT_SECRET", jwtSecret, missingConfigs);
        validateSecret("ERP_ENCRYPTION_KEY", encryptionKey, missingConfigs);

        if (!StringUtils.hasText(databasePassword)) {
            missingConfigs.add("SPRING_DATASOURCE_PASSWORD");
        }

        // Important configurations (warn but don't fail)
        if (!StringUtils.hasText(mailPassword)) {
            warnings.add("SPRING_MAIL_PASSWORD is not set - email functionality will be disabled");
        }

        if (!StringUtils.hasText(corsAllowedOrigins) || corsAllowedOrigins.contains("localhost")) {
            warnings.add("ERP_CORS_ALLOWED_ORIGINS contains localhost or is not set - review for production");
        }

        // Log warnings
        for (String warning : warnings) {
            logger.warn("Production configuration warning: {}", warning);
        }

        // Fail if critical configs are missing
        if (!missingConfigs.isEmpty()) {
            String errorMessage = String.format(
                    "Production environment validation failed. Missing or invalid configurations: %s. " +
                    "Please configure these environment variables before starting in production mode.",
                    String.join(", ", missingConfigs)
            );
            logger.error(errorMessage);
            throw new IllegalStateException(errorMessage);
        }

        logger.info("Production environment validation passed successfully.");
    }

    /**
     * Validates a secret value for minimum length and basic entropy.
     * Checks that the secret has minimum length and sufficient unique characters
     * to prevent weak secrets like repeated characters.
     */
    private void validateSecret(String name, String value, List<String> errors) {
        if (!StringUtils.hasText(value)) {
            errors.add(name + " is not set");
            return;
        }

        if (value.length() < MIN_SECRET_LENGTH) {
            errors.add(name + " must be at least " + MIN_SECRET_LENGTH + " bytes");
            return;
        }

        // Basic entropy check: require minimum unique characters
        Set<Character> uniqueChars = new HashSet<>();
        for (char c : value.toCharArray()) {
            uniqueChars.add(c);
        }
        if (uniqueChars.size() < MIN_UNIQUE_CHARS) {
            errors.add(name + " has insufficient entropy (requires at least " + MIN_UNIQUE_CHARS + " unique characters)");
        }
    }
}
