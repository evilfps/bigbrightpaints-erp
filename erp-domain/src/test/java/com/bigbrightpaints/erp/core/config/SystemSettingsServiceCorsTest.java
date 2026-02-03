package com.bigbrightpaints.erp.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemSettingsServiceCorsTest {

    @Mock
    private SystemSettingsRepository settingsRepository;

    @BeforeEach
    void setup() {
        when(settingsRepository.findAll()).thenReturn(List.of());
    }

    @Test
    void rejectsWildcardOriginWhenCredentialsEnabled() {
        assertThatThrownBy(() ->
                new SystemSettingsService(new EmailProperties(), settingsRepository, "*", true, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("*");
    }

    @Test
    void rejectsNonHttpsOrigins() {
        assertThatThrownBy(() ->
                new SystemSettingsService(new EmailProperties(), settingsRepository, "http://example.com", true, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    void allowsLocalhostHttpOriginsAndNormalizes() {
        SystemSettingsService service = new SystemSettingsService(
                new EmailProperties(),
                settingsRepository,
                "HTTP://LOCALHOST:3002/",
                true,
                true
        );

        CorsConfiguration configuration = service.buildCorsConfiguration();

        assertThat(configuration.getAllowedOrigins()).containsExactly("http://localhost:3002");
    }
}
