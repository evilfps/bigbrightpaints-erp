package com.bigbrightpaints.erp.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "erp.mail")
public class EmailProperties {

    private boolean enabled = false;
    private String fromAddress = "noreply@bigbrightpaints.com";
    private String baseUrl = "http://localhost:3004";
    private boolean sendCredentials = true;
    private boolean sendPasswordReset = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public boolean isSendCredentials() {
        return sendCredentials;
    }

    public void setSendCredentials(boolean sendCredentials) {
        this.sendCredentials = sendCredentials;
    }

    public boolean isSendPasswordReset() {
        return sendPasswordReset;
    }

    public void setSendPasswordReset(boolean sendPasswordReset) {
        this.sendPasswordReset = sendPasswordReset;
    }
}
