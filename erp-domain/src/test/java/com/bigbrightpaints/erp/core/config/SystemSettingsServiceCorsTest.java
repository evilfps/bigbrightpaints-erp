package com.bigbrightpaints.erp.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemSettingsServiceCorsTest {

    @Mock
    private SystemSettingsRepository settingsRepository;

    private final MockEnvironment nonProdEnvironment = new MockEnvironment();
    private final MockEnvironment prodEnvironment = new MockEnvironment().withProperty("spring.profiles.active", "prod");

    @BeforeEach
    void setup() {
        when(settingsRepository.findAll()).thenReturn(List.of());
    }

    @Test
    void rejectsWildcardOriginWhenCredentialsEnabled() {
        assertThatThrownBy(() ->
                new SystemSettingsService(new EmailProperties(), settingsRepository, nonProdEnvironment, "*", true, false, true, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("*");
    }

    @Test
    void rejectsPublicHttpOriginsWhenValidationDisabled() {
        assertThatThrownBy(() ->
                new SystemSettingsService(new EmailProperties(), settingsRepository, nonProdEnvironment, "http://example.com", false, false, true, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    void allowsLocalhostHttpOriginsAndNormalizes() {
        SystemSettingsService service = new SystemSettingsService(
                new EmailProperties(),
                settingsRepository,
                nonProdEnvironment,
                "HTTP://LOCALHOST:3002/",
                true,
                false,
                true,
                true,
                false
        );

        CorsConfiguration configuration = service.buildCorsConfiguration();

        assertThat(configuration.getAllowedOrigins()).containsExactly("http://localhost:3002");
    }

    @Test
    void allowsPrivateNetworkHttpOriginsWhenValidationDisabled() {
        SystemSettingsService service = new SystemSettingsService(
                new EmailProperties(),
                settingsRepository,
                nonProdEnvironment,
                "http://192.168.29.187:3002/",
                false,
                false,
                true,
                true,
                false
        );

        CorsConfiguration configuration = service.buildCorsConfiguration();

        assertThat(configuration.getAllowedOrigins()).containsExactly("http://192.168.29.187:3002");
    }

    @Test
    void rejectsPrivateNetworkHttpOriginsWhenValidationEnabled() {
        assertThatThrownBy(() ->
                new SystemSettingsService(
                        new EmailProperties(),
                        settingsRepository,
                        nonProdEnvironment,
                        "http://192.168.29.187:3002",
                        true,
                        false,
                        true,
                        true,
                        false
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localhost");
    }

    @Test
    void prodRejectsLocalHttpOriginsEvenWhenValidationDisabled() {
        assertThatThrownBy(() ->
                new SystemSettingsService(
                        new EmailProperties(),
                        settingsRepository,
                        prodEnvironment,
                        "http://localhost:3002",
                        false,
                        false,
                        true,
                        true,
                        false
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prod profile");
    }

    @Test
    void allowsTailscaleHttpOriginsWhenExplicitlyEnabledInProd() {
        SystemSettingsService service = new SystemSettingsService(
                new EmailProperties(),
                settingsRepository,
                prodEnvironment,
                "http://100.109.241.47:3000/",
                true,
                true,
                true,
                true,
                false
        );

        CorsConfiguration configuration = service.buildCorsConfiguration();

        assertThat(configuration.getAllowedOrigins()).containsExactly("http://100.109.241.47:3000");
    }

    @Test
    void rejectsTailscaleHttpOriginsInProdWithoutExplicitOptIn() {
        assertThatThrownBy(() ->
                new SystemSettingsService(
                        new EmailProperties(),
                        settingsRepository,
                        prodEnvironment,
                        "http://100.109.241.47:3000",
                        true,
                        false,
                        true,
                        true,
                        false
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allow-tailscale-http-origins");
    }
}
