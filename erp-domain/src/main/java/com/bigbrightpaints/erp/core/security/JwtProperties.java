package com.bigbrightpaints.erp.core.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    private static final Logger log = LoggerFactory.getLogger(JwtProperties.class);

    private String secret;
    private long accessTokenTtlSeconds = 900;
    private long refreshTokenTtlSeconds = 2_592_000;

    @PostConstruct
    void validate() {
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException("JWT secret must be provided via configuration/environment");
        }
        int secretBytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (secretBytes < 32) {
            throw new IllegalStateException("JWT secret must be at least 256 bits (32 bytes); current=" + secretBytes);
        }
        if ("changeme".equalsIgnoreCase(secret) || secret.toLowerCase().contains("changeme")) {
            log.warn("JWT secret is using a default-looking value; replace with a strong, random 32+ byte secret.");
        }
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
