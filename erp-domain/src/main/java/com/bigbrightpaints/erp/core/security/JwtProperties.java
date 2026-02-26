package com.bigbrightpaints.erp.core.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

@ConfigurationProperties(prefix = "jwt")
public class JwtProperties implements EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(JwtProperties.class);
    private static final String TEST_PROFILE = "test";
    private static final Set<String> UNSAFE_STATIC_SECRETS = Set.of(
            "changeme",
            "dev-only-jwt-secret-please-override-32",
            "mock-super-secret-key-0123456789-abcdefghijklmnopqrstuvwxyz",
            "test-secret-should-be-at-least-32-bytes-long-1234",
            "benchmarksecretkey32byteslongxxx");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private String secret;
    private long accessTokenTtlSeconds = 900;
    private long refreshTokenTtlSeconds = 2_592_000;
    private Environment environment = new StandardEnvironment();

    @PostConstruct
    void validate() {
        secret = StringUtils.hasText(secret) ? secret.trim() : secret;

        if (!StringUtils.hasText(secret)) {
            if (isTestOnlyRuntime()) {
                secret = generateEphemeralSecret();
                log.warn("JWT secret missing in test-only profile context {}; generated ephemeral in-memory secret",
                        activeProfilesForLogging());
                return;
            }
            throw new IllegalStateException("JWT secret must be provided via configuration/environment for non-test runtime profiles");
        }

        int secretBytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (secretBytes < 32) {
            throw new IllegalStateException("JWT secret must be at least 256 bits (32 bytes); current=" + secretBytes);
        }

        if (isUnsafeStaticSecret(secret)) {
            if (isTestOnlyRuntime()) {
                log.warn("JWT secret is using a known static placeholder in test-only profile context {}; override with env secret for non-test runtime",
                        activeProfilesForLogging());
                return;
            }
            throw new IllegalStateException("JWT secret uses an unsafe static placeholder; provide a strong random value via jwt.secret/JWT_SECRET");
        }
    }

    private boolean isUnsafeStaticSecret(String candidate) {
        String normalized = candidate.trim().toLowerCase(Locale.ROOT);
        if (UNSAFE_STATIC_SECRETS.contains(normalized)) {
            return true;
        }
        if (normalized.contains("changeme") || normalized.contains("please-override") || normalized.contains("replace-me")) {
            return true;
        }
        return normalized.startsWith("${") && normalized.endsWith("}");
    }

    private boolean isTestOnlyRuntime() {
        Set<String> profiles = activeProfiles();
        return !profiles.isEmpty() && profiles.stream().allMatch(TEST_PROFILE::equals);
    }

    private Set<String> activeProfiles() {
        Set<String> profiles = new LinkedHashSet<>();
        Stream.of(environment.getActiveProfiles())
                .map(profile -> profile == null ? "" : profile.trim().toLowerCase(Locale.ROOT))
                .filter(StringUtils::hasText)
                .forEach(profiles::add);
        if (profiles.isEmpty()) {
            Stream.of(environment.getDefaultProfiles())
                    .map(profile -> profile == null ? "" : profile.trim().toLowerCase(Locale.ROOT))
                    .filter(StringUtils::hasText)
                    .forEach(profiles::add);
        }
        profiles.remove("default");
        return profiles;
    }

    private String activeProfilesForLogging() {
        Set<String> profiles = activeProfiles();
        return profiles.isEmpty() ? "[none]" : String.join(",", profiles);
    }

    private String generateEphemeralSecret() {
        byte[] seed = new byte[48];
        SECURE_RANDOM.nextBytes(seed);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(seed);
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment == null ? new StandardEnvironment() : environment;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    public void setAccessTokenTtlSeconds(long accessTokenTtlSeconds) {
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }

    public long getRefreshTokenTtlSeconds() {
        return refreshTokenTtlSeconds;
    }

    public void setRefreshTokenTtlSeconds(long refreshTokenTtlSeconds) {
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }
}
