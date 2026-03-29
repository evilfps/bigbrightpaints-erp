package com.bigbrightpaints.erp.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;

import com.bigbrightpaints.erp.core.security.AuthScopeService;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class SystemSettingsServiceCorsTest {

  @Mock private SystemSettingsRepository settingsRepository;
  @Mock private AuthScopeService authScopeService;

  private final MockEnvironment nonProdEnvironment = new MockEnvironment();
  private final MockEnvironment prodEnvironment =
      new MockEnvironment().withProperty("spring.profiles.active", "prod");

  @BeforeEach
  void setup() {
    when(settingsRepository.findAll()).thenReturn(List.of());
  }

  @Test
  void rejectsWildcardOriginWhenCredentialsEnabled() {
    assertThatThrownBy(
            () ->
                new SystemSettingsService(
                    new EmailProperties(),
                    settingsRepository,
                    authScopeService,
                    nonProdEnvironment,
                    "*",
                    true,
                    false,
                    true,
                    true,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("*");
  }

  @Test
  void rejectsPublicHttpOriginsWhenValidationDisabled() {
    assertThatThrownBy(
            () ->
                new SystemSettingsService(
                    new EmailProperties(),
                    settingsRepository,
                    authScopeService,
                    nonProdEnvironment,
                    "http://example.com",
                    false,
                    false,
                    true,
                    true,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("https");
  }

  @Test
  void allowsLocalhostHttpOriginsAndNormalizes() {
    SystemSettingsService service =
        new SystemSettingsService(
            new EmailProperties(),
            settingsRepository,
            authScopeService,
            nonProdEnvironment,
            "HTTP://LOCALHOST:3002/",
            true,
            false,
            true,
            true,
            false);

    CorsConfiguration configuration = service.buildCorsConfiguration();

    assertThat(configuration.getAllowedOrigins()).containsExactly("http://localhost:3002");
  }

  @Test
  void rejectsPrivateNetworkHttpOriginsEvenWhenValidationDisabled() {
    assertThatThrownBy(
            () ->
                new SystemSettingsService(
                    new EmailProperties(),
                    settingsRepository,
                    authScopeService,
                    nonProdEnvironment,
                    "http://192.168.29.187:3002",
                    false,
                    false,
                    true,
                    true,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("localhost");
  }

  @Test
  void rejectsInvalidHttpOrigins_withTailscaleOptIn_mentionsCanonicalHttpPolicy() {
    assertThatThrownBy(
            () ->
                new SystemSettingsService(
                    new EmailProperties(),
                    settingsRepository,
                    authScopeService,
                    nonProdEnvironment,
                    "http://example.com",
                    true,
                    true,
                    true,
                    true,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("http allowed only for localhost")
        .hasMessageContaining("Tailscale 100.64.0.0/10");
  }

  @Test
  void prodRejectsLocalHttpOriginsEvenWhenValidationDisabled() {
    assertThatThrownBy(
            () ->
                new SystemSettingsService(
                    new EmailProperties(),
                    settingsRepository,
                    authScopeService,
                    prodEnvironment,
                    "http://localhost:3002",
                    false,
                    false,
                    true,
                    true,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("prod profile");
  }

  @Test
  void allowsTailscaleHttpOriginsWhenExplicitlyEnabledInProd() {
    SystemSettingsService service =
        new SystemSettingsService(
            new EmailProperties(),
            settingsRepository,
            authScopeService,
            prodEnvironment,
            "http://100.109.241.47:3000/",
            true,
            true,
            true,
            true,
            false);

    CorsConfiguration configuration = service.buildCorsConfiguration();

    assertThat(configuration.getAllowedOrigins()).containsExactly("http://100.109.241.47:3000");
  }

  @Test
  void rejectsTailscaleHttpOriginsInProdWithoutExplicitOptIn() {
    assertThatThrownBy(
            () ->
                new SystemSettingsService(
                    new EmailProperties(),
                    settingsRepository,
                    authScopeService,
                    prodEnvironment,
                    "http://100.109.241.47:3000",
                    true,
                    false,
                    true,
                    true,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("allow-tailscale-http-origins");
  }

  @Test
  void helper_isAllowedLocalHttpOrigin_onlyAllowsLoopbackOrOptedInTailscale() {
    SystemSettingsService loopbackOnly =
        new SystemSettingsService(
            new EmailProperties(),
            settingsRepository,
            authScopeService,
            nonProdEnvironment,
            "",
            true,
            false,
            true,
            true,
            false);
    SystemSettingsService tailscaleAllowed =
        new SystemSettingsService(
            new EmailProperties(),
            settingsRepository,
            authScopeService,
            nonProdEnvironment,
            "",
            true,
            true,
            true,
            true,
            false);

    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    loopbackOnly, "isLocalOrPrivateHttpOrigin", "http://localhost:3002"))
        .isTrue();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    loopbackOnly, "isLocalOrPrivateHttpOrigin", "http://100.109.241.47:3000"))
        .isFalse();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    tailscaleAllowed, "isLocalOrPrivateHttpOrigin", "http://192.168.29.187:3002"))
        .isFalse();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    tailscaleAllowed, "isLocalOrPrivateHttpOrigin", "http://100.109.241.47:3000"))
        .isTrue();
  }

  @Test
  void helper_isDisallowedProdHttpOrigin_blocksLoopbackAndUnoptedTailscale() {
    SystemSettingsService tailscaleBlocked =
        new SystemSettingsService(
            new EmailProperties(),
            settingsRepository,
            authScopeService,
            prodEnvironment,
            "",
            true,
            false,
            true,
            true,
            false);
    SystemSettingsService tailscaleAllowed =
        new SystemSettingsService(
            new EmailProperties(),
            settingsRepository,
            authScopeService,
            prodEnvironment,
            "",
            true,
            true,
            true,
            true,
            false);

    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    tailscaleBlocked, "isDisallowedProdHttpOrigin", "http://localhost:3002"))
        .isTrue();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    tailscaleBlocked, "isDisallowedProdHttpOrigin", "http://100.109.241.47:3000"))
        .isTrue();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    tailscaleBlocked, "isDisallowedProdHttpOrigin", "http://192.168.29.187:3002"))
        .isFalse();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    tailscaleBlocked, "isDisallowedProdHttpOrigin", "https://example.com"))
        .isFalse();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    tailscaleAllowed, "isDisallowedProdHttpOrigin", "http://100.109.241.47:3000"))
        .isFalse();
  }
}
