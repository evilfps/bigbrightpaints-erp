package com.bigbrightpaints.erp.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "erp.licensing")
public class LicensingProperties {

    private long productId = 31720;
    private String licenseKey;
    private String algorithm = "SKM15";
    private String created = "2025-12-08";
    private String description = "ERP";
    /**
     * When true, the application will fail fast if a license key is not provided.
     * Leave false for local development and tests.
     */
    private boolean enforce = false;
    /**
     * Optional CryptoLens access token if you want to call the activation API.
     * Not enforced by default to avoid leaking secrets in source control.
     */
    private String accessToken;

    public long getProductId() {
        return productId;
    }

    public void setProductId(long productId) {
        this.productId = productId;
    }

    public String getLicenseKey() {
        return licenseKey;
    }

    public void setLicenseKey(String licenseKey) {
        this.licenseKey = licenseKey;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public String getCreated() {
        return created;
    }

    public void setCreated(String created) {
        this.created = created;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isEnforce() {
        return enforce;
    }

    public void setEnforce(boolean enforce) {
        this.enforce = enforce;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public boolean hasLicenseKey() {
        return StringUtils.hasText(licenseKey);
    }
}
