package com.bigbrightpaints.erp.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.web.cors.CorsConfiguration;

import com.bigbrightpaints.erp.modules.admin.dto.SystemSettingsDto;
import com.bigbrightpaints.erp.modules.admin.dto.SystemSettingsUpdateRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.Map;
import java.net.URI;

/**
 * Holds runtime-tunable settings that can be updated via the admin API.
 * Persists changes to DB (system_settings), but still initializes from config defaults.
 */
@Service
public class SystemSettingsService {

    private final EmailProperties emailProperties;
    private final SystemSettingsRepository settingsRepository;
    private final Environment environment;
    private final CopyOnWriteArrayList<String> allowedOrigins = new CopyOnWriteArrayList<>();
    private final boolean environmentValidationEnabled;
    private final boolean allowTailscaleHttpOrigins;
    private volatile boolean autoApprovalEnabled;
    private volatile boolean periodLockEnforced;
    private volatile boolean exportApprovalRequired;

    private static final String KEY_ALLOWED_ORIGINS = "cors.allowed-origins";
    private static final String KEY_AUTO_APPROVAL = "auto-approval.enabled";
    private static final String KEY_PERIOD_LOCK = "period-lock.enforced";
    private static final String KEY_MAIL_ENABLED = "mail.enabled";
    private static final String KEY_MAIL_FROM = "mail.from";
    private static final String KEY_MAIL_BASE_URL = "mail.base-url";
    private static final String KEY_SEND_CREDS = "mail.send-credentials";
    private static final String KEY_SEND_RESET = "mail.send-password-reset";
    private static final String KEY_EXPORT_REQUIRE_APPROVAL = "export.require-approval";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

    public SystemSettingsService(EmailProperties emailProperties,
                                 SystemSettingsRepository settingsRepository,
                                 Environment environment,
                                 @Value("${erp.cors.allowed-origins:http://localhost:3002}") String corsOrigins,
                                 @Value("${erp.environment.validation.enabled:false}") boolean environmentValidationEnabled,
                                 @Value("${erp.cors.allow-tailscale-http-origins:false}") boolean allowTailscaleHttpOrigins,
                                 @Value("${erp.auto-approval.enabled:true}") boolean autoApprovalEnabled,
                                 @Value("${erp.period-lock.enforced:true}") boolean periodLockEnforced,
                                 @Value("${erp.export.require-approval:false}") boolean exportApprovalRequired) {
        this.emailProperties = emailProperties;
        this.settingsRepository = settingsRepository;
        this.environment = environment;
        this.environmentValidationEnabled = environmentValidationEnabled;
        this.allowTailscaleHttpOrigins = allowTailscaleHttpOrigins;
        // Load persisted values (if any), else fall back to config defaults
        Map<String, String> persisted = settingsRepository.findAll().stream()
                .collect(Collectors.toMap(SystemSetting::getKey, SystemSetting::getValue));

        this.allowedOrigins.addAll(parseOrigins(corsOrigins));
        this.autoApprovalEnabled = parseBool(persisted.getOrDefault(KEY_AUTO_APPROVAL, String.valueOf(autoApprovalEnabled)));
        this.periodLockEnforced = parseBool(persisted.getOrDefault(KEY_PERIOD_LOCK, String.valueOf(periodLockEnforced)));
        this.exportApprovalRequired = parseBool(persisted.getOrDefault(KEY_EXPORT_REQUIRE_APPROVAL,
                String.valueOf(exportApprovalRequired)));

        // Apply persisted mail settings if present
        if (persisted.containsKey(KEY_MAIL_ENABLED)) emailProperties.setEnabled(parseBool(persisted.get(KEY_MAIL_ENABLED)));
        if (persisted.containsKey(KEY_MAIL_FROM)) emailProperties.setFromAddress(persisted.get(KEY_MAIL_FROM));
        if (persisted.containsKey(KEY_MAIL_BASE_URL)) emailProperties.setBaseUrl(persisted.get(KEY_MAIL_BASE_URL));
        if (persisted.containsKey(KEY_SEND_CREDS)) emailProperties.setSendCredentials(parseBool(persisted.get(KEY_SEND_CREDS)));
        if (persisted.containsKey(KEY_SEND_RESET)) emailProperties.setSendPasswordReset(parseBool(persisted.get(KEY_SEND_RESET)));
        if (persisted.containsKey(KEY_ALLOWED_ORIGINS)) {
            this.allowedOrigins.clear();
            this.allowedOrigins.addAll(parseOrigins(persisted.get(KEY_ALLOWED_ORIGINS)));
        }
    }

    public List<String> getAllowedOrigins() {
        return List.copyOf(allowedOrigins);
    }

    public void setAllowedOrigins(List<String> origins) {
        if (origins == null || origins.isEmpty()) {
            return;
        }
        List<String> normalizedOrigins = validateOrigins(origins);
        allowedOrigins.clear();
        allowedOrigins.addAll(normalizedOrigins);
        settingsRepository.save(new SystemSetting(KEY_ALLOWED_ORIGINS, String.join(",", allowedOrigins)));
    }

    public boolean isAutoApprovalEnabled() {
        return autoApprovalEnabled;
    }

    public void setAutoApprovalEnabled(boolean autoApprovalEnabled) {
        this.autoApprovalEnabled = autoApprovalEnabled;
        settingsRepository.save(new SystemSetting(KEY_AUTO_APPROVAL, String.valueOf(autoApprovalEnabled)));
    }

    public boolean isPeriodLockEnforced() {
        return periodLockEnforced;
    }

    public void setPeriodLockEnforced(boolean periodLockEnforced) {
        this.periodLockEnforced = periodLockEnforced;
        settingsRepository.save(new SystemSetting(KEY_PERIOD_LOCK, String.valueOf(periodLockEnforced)));
    }

    public boolean isExportApprovalRequired() {
        return exportApprovalRequired;
    }

    public void setExportApprovalRequired(boolean exportApprovalRequired) {
        this.exportApprovalRequired = exportApprovalRequired;
        settingsRepository.save(new SystemSetting(KEY_EXPORT_REQUIRE_APPROVAL, String.valueOf(exportApprovalRequired)));
    }

    public EmailProperties getEmailProperties() {
        return emailProperties;
    }

    public SystemSettingsDto snapshot() {
        return new SystemSettingsDto(
                List.copyOf(allowedOrigins),
                autoApprovalEnabled,
                periodLockEnforced,
                exportApprovalRequired,
                emailProperties.isEnabled(),
                emailProperties.getFromAddress(),
                emailProperties.getBaseUrl(),
                emailProperties.isSendCredentials(),
                emailProperties.isSendPasswordReset()
        );
    }

    public SystemSettingsDto update(SystemSettingsUpdateRequest request) {
        if (request.allowedOrigins() != null && !request.allowedOrigins().isEmpty()) {
            setAllowedOrigins(request.allowedOrigins());
        }
        if (request.autoApprovalEnabled() != null) {
            setAutoApprovalEnabled(request.autoApprovalEnabled());
        }
        if (request.periodLockEnforced() != null) {
            setPeriodLockEnforced(request.periodLockEnforced());
        }
        if (request.exportApprovalRequired() != null) {
            setExportApprovalRequired(request.exportApprovalRequired());
        }
        if (request.mailEnabled() != null) {
            emailProperties.setEnabled(request.mailEnabled());
            settingsRepository.save(new SystemSetting(KEY_MAIL_ENABLED, String.valueOf(request.mailEnabled())));
        }
        if (request.mailFromAddress() != null) {
            emailProperties.setFromAddress(request.mailFromAddress());
            settingsRepository.save(new SystemSetting(KEY_MAIL_FROM, request.mailFromAddress()));
        }
        if (request.mailBaseUrl() != null) {
            emailProperties.setBaseUrl(request.mailBaseUrl());
            settingsRepository.save(new SystemSetting(KEY_MAIL_BASE_URL, request.mailBaseUrl()));
        }
        if (request.sendCredentials() != null) {
            emailProperties.setSendCredentials(request.sendCredentials());
            settingsRepository.save(new SystemSetting(KEY_SEND_CREDS, String.valueOf(request.sendCredentials())));
        }
        if (request.sendPasswordReset() != null) {
            emailProperties.setSendPasswordReset(request.sendPasswordReset());
            settingsRepository.save(new SystemSetting(KEY_SEND_RESET, String.valueOf(request.sendPasswordReset())));
        }
        return snapshot();
    }

    public CorsConfiguration buildCorsConfiguration() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(new ArrayList<>(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        return configuration;
    }

    private List<String> parseOrigins(String corsOrigins) {
        if (corsOrigins == null || corsOrigins.isBlank()) {
            return List.of();
        }
        return validateOrigins(Arrays.stream(corsOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList()));
    }

    private List<String> validateOrigins(List<String> origins) {
        if (origins == null || origins.isEmpty()) {
            return List.of();
        }
        if (isProdProfileActive()) {
            if (origins.stream().anyMatch(this::isDisallowedProdHttpOrigin)) {
                throw new IllegalArgumentException(
                        "Invalid CORS origins for prod profile (only explicit https origins are allowed, unless "
                                + "erp.cors.allow-tailscale-http-origins=true for Tailscale 100.64.0.0/10 origins): " + origins);
            }
        }
        List<String> invalidOrigins = new ArrayList<>();
        Set<String> normalizedOrigins = new LinkedHashSet<>();
        for (String rawOrigin : origins) {
            if (rawOrigin == null || rawOrigin.isBlank()) {
                continue;
            }
            String origin = rawOrigin.trim();
            if (origin.contains("*")) {
                invalidOrigins.add(origin);
                continue;
            }
            URI uri;
            try {
                uri = URI.create(origin);
            } catch (IllegalArgumentException ex) {
                invalidOrigins.add(origin);
                continue;
            }
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                invalidOrigins.add(origin);
                continue;
            }
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                invalidOrigins.add(origin);
                continue;
            }
            String path = uri.getPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                invalidOrigins.add(origin);
                continue;
            }
            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            boolean httpsAllowed = "https".equals(normalizedScheme);
            boolean httpAllowed = "http".equals(normalizedScheme)
                    && (LOOPBACK_HOSTS.contains(normalizedHost)
                    || (!environmentValidationEnabled && isPrivateNetworkIpv4Literal(normalizedHost))
                    || (allowTailscaleHttpOrigins && isTailscaleIpv4Literal(normalizedHost)));
            if (!httpsAllowed && !httpAllowed) {
                invalidOrigins.add(origin);
                continue;
            }
            String normalizedOrigin = normalizedScheme + "://" + normalizedHost;
            if (uri.getPort() != -1) {
                normalizedOrigin = normalizedOrigin + ":" + uri.getPort();
            }
            normalizedOrigins.add(normalizedOrigin);
        }
        if (!invalidOrigins.isEmpty()) {
            String httpPolicy = environmentValidationEnabled
                    ? "http allowed only for localhost"
                    : "http allowed only for localhost or private-network IPv4 when erp.environment.validation.enabled=false";
            if (allowTailscaleHttpOrigins) {
                httpPolicy = httpPolicy + ", plus Tailscale 100.64.0.0/10 when erp.cors.allow-tailscale-http-origins=true";
            }
            throw new IllegalArgumentException(
                    "Invalid CORS origins (must be https, without wildcards; " + httpPolicy + "): "
                            + invalidOrigins);
        }
        return new ArrayList<>(normalizedOrigins);
    }

    private boolean isPrivateNetworkIpv4Literal(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].chars().allMatch(Character::isDigit)) {
                return false;
            }
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ex) {
                return false;
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return false;
            }
        }
        return octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }

    private boolean isTailscaleIpv4Literal(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].chars().allMatch(Character::isDigit)) {
                return false;
            }
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ex) {
                return false;
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return false;
            }
        }
        return octets[0] == 100 && octets[1] >= 64 && octets[1] <= 127;
    }

    private boolean parseBool(String value) {
        return value != null && Boolean.parseBoolean(value.trim());
    }

    private boolean isProdProfileActive() {
        return environment != null && environment.acceptsProfiles(Profiles.of("prod"));
    }

    private boolean isLocalOrPrivateHttpOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(origin.trim());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || !"http".equalsIgnoreCase(scheme)) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return LOOPBACK_HOSTS.contains(normalizedHost) || isPrivateNetworkIpv4Literal(normalizedHost);
    }

    private boolean isDisallowedProdHttpOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(origin.trim());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || !"http".equalsIgnoreCase(scheme)) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (allowTailscaleHttpOrigins && isTailscaleIpv4Literal(normalizedHost)) {
            return false;
        }
        return LOOPBACK_HOSTS.contains(normalizedHost) || isPrivateNetworkIpv4Literal(normalizedHost) || isTailscaleIpv4Literal(normalizedHost);
    }
}
