package com.bigbrightpaints.erp.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.TotpTestUtils;

public class MfaControllerIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "MFA";
  private static final String USER_EMAIL = "mfa-user@bbp.com";
  private static final String USER_PASSWORD = "ChangeMe123!";

  @Autowired private TestRestTemplate rest;

  @Autowired private UserAccountRepository userAccountRepository;

  @BeforeEach
  void seedUser() {
    configureRestTemplate();
    UserAccount user =
        dataSeeder.ensureUser(
            USER_EMAIL, USER_PASSWORD, "MFA User", COMPANY_CODE, List.of("ROLE_ADMIN"));
    user.setMfaEnabled(false);
    user.setMfaSecret(null);
    user.setMfaRecoveryCodeHashes(List.of());
    userAccountRepository.save(user);
  }

  private void configureRestTemplate() {
    CloseableHttpClient client =
        HttpClients.custom()
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .disableAuthCaching()
            .build();
    HttpComponentsClientHttpRequestFactory factory =
        new HttpComponentsClientHttpRequestFactory(client);
    factory.setConnectTimeout(Duration.ofSeconds(10));
    factory.setConnectionRequestTimeout(Duration.ofSeconds(10));
    rest.getRestTemplate().setRequestFactory(factory);
  }

  @Test
  void enrollment_and_activation_require_totp_for_login() {
    String token = obtainAccessToken(null, null);
    SetupPayload setup = startEnrollment(token);
    assertThat(setup.qrUri()).contains("mfa-user");
    assertThat(setup.qrUri()).contains("MFA");

    String activationCode = TotpTestUtils.generateCurrentCode(setup.secret());
    ResponseEntity<Map> activateResp =
        postWithBearer("/api/v1/auth/mfa/activate", Map.of("code", activationCode), token);
    assertThat(activateResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> activateData = apiData(activateResp);
    assertThat(activateData.get("enabled")).isEqualTo(Boolean.TRUE);

    ResponseEntity<Map> missingMfaLogin = login(null, null);
    assertThat(missingMfaLogin.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    assertMfaChallenge(missingMfaLogin);

    String loginCode = TotpTestUtils.generateCurrentCode(setup.secret());
    ResponseEntity<Map> loginResponse = login(loginCode, null);
    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(loginResponse.getBody()).isNotNull();
    assertThat(loginResponse.getBody().get("accessToken")).isNotNull();
  }

  @Test
  void recovery_code_is_consumed_after_login() {
    String token = obtainAccessToken(null, null);
    SetupPayload setup = startEnrollment(token);

    String activationCode = TotpTestUtils.generateCurrentCode(setup.secret());
    postWithBearer("/api/v1/auth/mfa/activate", Map.of("code", activationCode), token);

    String recoveryCode = setup.recoveryCodes().getFirst();

    ResponseEntity<Map> firstLogin = login(null, recoveryCode);
    assertThat(firstLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(firstLogin.getBody()).isNotNull();
    assertThat(firstLogin.getBody().get("accessToken")).isNotNull();

    ResponseEntity<Map> secondLogin = login(null, recoveryCode);
    assertThat(secondLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private SetupPayload startEnrollment(String token) {
    ResponseEntity<Map> setupResp = postWithBearer("/api/v1/auth/mfa/setup", null, token);
    assertThat(setupResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> setupData = apiData(setupResp);
    String secret = setupData.get("secret").toString();
    String qrUri = setupData.get("qrUri").toString();
    @SuppressWarnings("unchecked")
    List<String> recoveryCodes =
        ((List<Object>) setupData.get("recoveryCodes")).stream().map(Object::toString).toList();
    return new SetupPayload(secret, qrUri, recoveryCodes);
  }

  private String obtainAccessToken(String mfaCode, String recoveryCode) {
    ResponseEntity<Map> response = login(mfaCode, recoveryCode);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    String token = (String) response.getBody().get("accessToken");
    assertThat(token).isNotBlank();
    return token;
  }

  private ResponseEntity<Map> login(String mfaCode, String recoveryCode) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("email", USER_EMAIL);
    payload.put("password", USER_PASSWORD);
    payload.put("companyCode", COMPANY_CODE);
    if (mfaCode != null) {
      payload.put("mfaCode", mfaCode);
    }
    if (recoveryCode != null) {
      payload.put("recoveryCode", recoveryCode);
    }
    return rest.postForEntity("/api/v1/auth/login", payload, Map.class);
  }

  private ResponseEntity<Map> postWithBearer(String path, Map<String, ?> body, String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<Map<String, ?>> entity = new HttpEntity<>(body, headers);
    return rest.exchange(path, HttpMethod.POST, entity, Map.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> apiData(ResponseEntity<Map> response) {
    Map<String, Object> body = response.getBody();
    Assertions.assertThat(body).isNotNull();
    return (Map<String, Object>) body.get("data");
  }

  @SuppressWarnings("unchecked")
  private void assertMfaChallenge(ResponseEntity<Map> response) {
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("success")).isEqualTo(Boolean.FALSE);
    Map<String, Object> data = (Map<String, Object>) body.get("data");
    assertThat(data).isNotNull();
    assertThat(data.get("required")).isEqualTo(Boolean.TRUE);
  }

  private record SetupPayload(String secret, String qrUri, List<String> recoveryCodes) {}
}
