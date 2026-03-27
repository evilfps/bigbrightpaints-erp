package com.bigbrightpaints.erp.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

class AuthPlatformScopeCodeIT extends AbstractIntegrationTest {

  private static final String DEFAULT_PLATFORM_CODE = AuthScopeService.DEFAULT_PLATFORM_AUTH_CODE;
  private static final String UPDATED_PLATFORM_CODE = "ROOTCTRL";
  private static final String COLLIDING_TENANT_CODE = "MOCK";
  private static final String SUPER_ADMIN_EMAIL = "platform-root@bbp.com";
  private static final String PASSWORD = "Passw0rd!";

  @Autowired private TestRestTemplate rest;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private SystemSettingsRepository systemSettingsRepository;

  @BeforeEach
  void setUp() {
    systemSettingsRepository.save(
        new SystemSetting(AuthScopeService.KEY_PLATFORM_AUTH_CODE, DEFAULT_PLATFORM_CODE));
    userAccountRepository
        .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(SUPER_ADMIN_EMAIL, UPDATED_PLATFORM_CODE)
        .ifPresent(userAccountRepository::delete);
    dataSeeder.ensureUser(
        SUPER_ADMIN_EMAIL,
        PASSWORD,
        "Platform Root",
        DEFAULT_PLATFORM_CODE,
        List.of("ROLE_SUPER_ADMIN"));
    userAccountRepository
        .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(SUPER_ADMIN_EMAIL, DEFAULT_PLATFORM_CODE)
        .ifPresent(this::resetSeededUserState);
  }

  @Test
  void platformAuthCodeUpdate_switchesExistingPlatformLoginImmediately() {
    String currentToken = login(DEFAULT_PLATFORM_CODE);

    ResponseEntity<Map> updateResponse =
        rest.exchange(
            "/api/v1/admin/settings",
            HttpMethod.PUT,
            new HttpEntity<>(
                Map.of("platformAuthCode", UPDATED_PLATFORM_CODE),
                jsonHeaders(currentToken, DEFAULT_PLATFORM_CODE)),
            Map.class);

    assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> updateData = responseData(updateResponse);
    assertThat(updateData.get("platformAuthCode")).isEqualTo(UPDATED_PLATFORM_CODE);

    ResponseEntity<Map> oldLoginResponse = loginResponse(DEFAULT_PLATFORM_CODE);
    assertThat(oldLoginResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    ResponseEntity<Map> newLoginResponse = loginResponse(UPDATED_PLATFORM_CODE);
    assertThat(newLoginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    String updatedToken = responseBody(newLoginResponse).get("accessToken").toString();

    ResponseEntity<Map> meResponse =
        rest.exchange(
            "/api/v1/auth/me",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(updatedToken, UPDATED_PLATFORM_CODE)),
            Map.class);
    assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(responseData(meResponse).get("companyCode")).isEqualTo(UPDATED_PLATFORM_CODE);

    ResponseEntity<Map> dashboardResponse =
        rest.exchange(
            "/api/v1/superadmin/dashboard",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(updatedToken, UPDATED_PLATFORM_CODE)),
            Map.class);
    assertThat(dashboardResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    assertThat(
            userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
                SUPER_ADMIN_EMAIL, DEFAULT_PLATFORM_CODE))
        .isEmpty();
    UserAccount updatedAccount =
        userAccountRepository
            .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
                SUPER_ADMIN_EMAIL, UPDATED_PLATFORM_CODE)
            .orElseThrow();
    assertThat(updatedAccount.getCompany()).isNull();
  }

  @Test
  void platformAuthCodeUpdate_rejectsTenantCompanyCollision() {
    dataSeeder.ensureCompany(COLLIDING_TENANT_CODE, "Mock Ltd");
    String currentToken = login(DEFAULT_PLATFORM_CODE);

    ResponseEntity<Map> updateResponse =
        rest.exchange(
            "/api/v1/admin/settings",
            HttpMethod.PUT,
            new HttpEntity<>(
                Map.of("platformAuthCode", COLLIDING_TENANT_CODE),
                jsonHeaders(currentToken, DEFAULT_PLATFORM_CODE)),
            Map.class);

    assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
                SUPER_ADMIN_EMAIL, DEFAULT_PLATFORM_CODE))
        .isPresent();
    assertThat(loginResponse(DEFAULT_PLATFORM_CODE).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private void resetSeededUserState(UserAccount user) {
    user.setEnabled(true);
    user.setMustChangePassword(false);
    user.setFailedLoginAttempts(0);
    user.setLockedUntil(null);
    userAccountRepository.save(user);
  }

  private String login(String companyCode) {
    ResponseEntity<Map> response = loginResponse(companyCode);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return responseBody(response).get("accessToken").toString();
  }

  private ResponseEntity<Map> loginResponse(String companyCode) {
    return rest.postForEntity(
        "/api/v1/auth/login",
        Map.of(
            "email", SUPER_ADMIN_EMAIL,
            "password", PASSWORD,
            "companyCode", companyCode),
        Map.class);
  }

  private HttpHeaders jsonHeaders(String token, String companyCode) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(token);
    headers.set("X-Company-Code", companyCode);
    return headers;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> responseBody(ResponseEntity<Map> response) {
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    return body;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> responseData(ResponseEntity<Map> response) {
    Map<String, Object> body = responseBody(response);
    Map<String, Object> data = (Map<String, Object>) body.get("data");
    assertThat(data).isNotNull();
    return data;
  }
}
