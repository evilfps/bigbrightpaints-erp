package com.bigbrightpaints.erp.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetToken;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetTokenRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
class AuthPasswordResetPublicContractIT extends AbstractIntegrationTest {

  private static final String SUPERADMIN_EMAIL = "superadmin.reset.contract@bbp.com";
  private static final String SUPERADMIN_PASSWORD = "Admin@12345";
  private static final String PRIMARY_COMPANY = "RESETA";
  private static final String SECONDARY_COMPANY = "RESETB";

  @Autowired private TestRestTemplate rest;

  @SpyBean private EmailService emailService;

  @SpyBean private PasswordResetTokenRepository passwordResetTokenRepository;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private AuditLogRepository auditLogRepository;

  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void seedSuperAdmin() {
    resetScopedSuperAdmin(PRIMARY_COMPANY);
    resetScopedSuperAdmin(SECONDARY_COMPANY);
  }

  private void resetScopedSuperAdmin(String companyCode) {
    UserAccount user =
        dataSeeder.ensureUser(
            SUPERADMIN_EMAIL,
            SUPERADMIN_PASSWORD,
            "Reset Super Admin",
            companyCode,
            List.of("ROLE_SUPER_ADMIN"));
    user.setEnabled(true);
    user.setMustChangePassword(false);
    user.setFailedLoginAttempts(0);
    user.setLockedUntil(null);
    userAccountRepository.saveAndFlush(user);
    jdbcTemplate.update("delete from password_reset_tokens where user_id = ?", user.getId());
  }

  @Test
  void forgotEndpoint_isPublicAndAntiEnumerationSafeAcrossTenantHeaders() {
    ResponseEntity<Map> knownUserResponse = postForgot(SUPERADMIN_EMAIL, PRIMARY_COMPANY);
    ResponseEntity<Map> unknownUserResponse =
        postForgot("unknown.superadmin@bbp.com", PRIMARY_COMPANY);

    assertThat(knownUserResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(unknownUserResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(knownUserResponse.getBody()).isNotNull();
    assertThat(unknownUserResponse.getBody()).isNotNull();
    assertThat(knownUserResponse.getBody().get("success")).isEqualTo(true);
    assertThat(unknownUserResponse.getBody().get("success")).isEqualTo(true);
    assertThat(knownUserResponse.getBody().get("message"))
        .isEqualTo("If the email exists, a reset link has been sent");
    assertThat(unknownUserResponse.getBody().get("message"))
        .isEqualTo("If the email exists, a reset link has been sent");
  }

  @Test
  void forgotEndpoint_keepsDisabledUsersMasked() {
    UserAccount disabledUser =
        userAccountRepository
            .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(SUPERADMIN_EMAIL, PRIMARY_COMPANY)
            .orElseThrow();
    disabledUser.setEnabled(false);
    userAccountRepository.saveAndFlush(disabledUser);

    ResponseEntity<Map> disabledUserResponse = postForgot(SUPERADMIN_EMAIL, PRIMARY_COMPANY);

    assertThat(disabledUserResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(disabledUserResponse.getBody()).isNotNull();
    assertThat(disabledUserResponse.getBody().get("success")).isEqualTo(true);
    assertThat(disabledUserResponse.getBody().get("message"))
        .isEqualTo("If the email exists, a reset link has been sent");
    verify(emailService, never())
        .sendPasswordResetEmailRequired(
            eq(SUPERADMIN_EMAIL), eq("Reset Super Admin"), anyString(), eq(PRIMARY_COMPANY));
  }

  @Test
  void resetEndpoint_usesTokenValidationNotTenantContextForFailureDecision() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Company-Code", "UNRELATED-TENANT");
    Map<String, Object> payload =
        Map.of(
            "token", "missing-token",
            "newPassword", "NewPass123",
            "confirmPassword", "NewPass123");

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/auth/password/reset",
            HttpMethod.POST,
            new HttpEntity<>(payload, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("message")).isEqualTo("Invalid or expired token");
  }

  @Test
  void forgotEndpoint_persistenceFailureStaysMaskedAndSendsNoEmail() {
    doThrow(new DataAccessResourceFailureException("db unavailable"))
        .when(passwordResetTokenRepository)
        .saveAndFlush(any(PasswordResetToken.class));

    ResponseEntity<Map> knownUserResponse = postForgot(SUPERADMIN_EMAIL, PRIMARY_COMPANY);
    ResponseEntity<Map> unknownUserResponse =
        postForgot("unknown.superadmin@bbp.com", PRIMARY_COMPANY);

    assertThat(knownUserResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(unknownUserResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(knownUserResponse.getBody()).isNotNull();
    assertThat(unknownUserResponse.getBody()).isNotNull();
    assertThat(knownUserResponse.getBody().get("message"))
        .isEqualTo("If the email exists, a reset link has been sent");
    assertThat(unknownUserResponse.getBody().get("message"))
        .isEqualTo("If the email exists, a reset link has been sent");
    verify(emailService, never())
        .sendPasswordResetEmailRequired(
            eq(SUPERADMIN_EMAIL), eq("Reset Super Admin"), anyString(), eq(PRIMARY_COMPANY));
  }

  @Test
  void forgotPersistenceFailureKeepsPreExistingResetTokenUsableAndDoesNotDispatchEmail() {
    String targetEmail = "preexisting.reset.user@bbp.com";
    UserAccount user =
        dataSeeder.ensureUser(
            targetEmail,
            "Admin@12345",
            "Preexisting Reset User",
            PRIMARY_COMPANY,
            List.of("ROLE_SUPER_ADMIN"));
    String preExistingToken = "preexisting-reset-token";
    passwordResetTokenRepository.saveAndFlush(
        PasswordResetToken.digestOnly(
            user, passwordResetDigest(preExistingToken), Instant.now().plusSeconds(600)));

    doThrow(new DataAccessResourceFailureException("db unavailable"))
        .when(passwordResetTokenRepository)
        .saveAndFlush(any(PasswordResetToken.class));

    ResponseEntity<Map> forgotResponse = postForgot(targetEmail, PRIMARY_COMPANY);

    assertThat(forgotResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(forgotResponse.getBody()).isNotNull();
    assertThat(forgotResponse.getBody().get("message"))
        .isEqualTo("If the email exists, a reset link has been sent");
    verify(emailService, never())
        .sendPasswordResetEmailRequired(
            eq(targetEmail), eq("Preexisting Reset User"), anyString(), eq(PRIMARY_COMPANY));

    ResponseEntity<Map> resetResponse = postReset(preExistingToken, "NewPass123!");
    assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resetResponse.getBody()).isNotNull();
    assertThat(resetResponse.getBody().get("success")).isEqualTo(true);
  }

  @Test
  void forgotEndpoint_deliveryFailureStaysMaskedAndCleansUpIssuedToken() {
    String targetEmail = "delivery.failure.reset.user@bbp.com";
    UserAccount user =
        dataSeeder.ensureUser(
            targetEmail,
            "Admin@12345",
            "Delivery Failure User",
            PRIMARY_COMPANY,
            List.of("ROLE_SUPER_ADMIN"));

    doThrow(new RuntimeException("smtp down"))
        .when(emailService)
        .sendPasswordResetEmailRequired(
            eq(targetEmail), eq("Delivery Failure User"), anyString(), eq(PRIMARY_COMPANY));

    ResponseEntity<Map> forgotResponse = postForgot(targetEmail, PRIMARY_COMPANY);

    assertThat(forgotResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(forgotResponse.getBody()).isNotNull();
    assertThat(forgotResponse.getBody().get("message"))
        .isEqualTo("If the email exists, a reset link has been sent");

    Integer tokenCount =
        jdbcTemplate.queryForObject(
            "select count(*) from password_reset_tokens where user_id = ?",
            Integer.class,
            user.getId());
    assertThat(tokenCount).isZero();
  }

  @Test
  void resetEndpoint_rejectsLegacyRawTokenStoredRows() {
    String targetEmail = "legacy.reset.user@bbp.com";
    UserAccount user =
        dataSeeder.ensureUser(
            targetEmail,
            "Admin@12345",
            "Legacy Reset User",
            PRIMARY_COMPANY,
            List.of("ROLE_SUPER_ADMIN"));
    String rawLegacyToken = "legacy-raw-reset-token";
    passwordResetTokenRepository.saveAndFlush(
        new PasswordResetToken(user, rawLegacyToken, Instant.now().plusSeconds(600)));

    ResponseEntity<Map> resetResponse = postReset(rawLegacyToken, "NewPass123!");

    assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(resetResponse.getBody()).isNotNull();
    assertThat(resetResponse.getBody().get("message")).isEqualTo("Invalid or expired token");
  }

  @Test
  void canonicalRecoveryFlow_forgotResetLoginRefreshAndMe_succeeds_withDigestBackedResetToken() {
    UserAccount user =
        userAccountRepository
            .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(SUPERADMIN_EMAIL, PRIMARY_COMPANY)
            .orElseThrow();
    user.setEnabled(true);
    user.setLockedUntil(null);
    user.setFailedLoginAttempts(0);
    userAccountRepository.saveAndFlush(user);

    List<String> deliveredTokens = Collections.synchronizedList(new ArrayList<>());
    doAnswer(
            invocation -> {
              deliveredTokens.add(invocation.getArgument(2, String.class));
              return null;
            })
        .when(emailService)
        .sendPasswordResetEmailRequired(
            eq(SUPERADMIN_EMAIL), eq("Reset Super Admin"), anyString(), eq(PRIMARY_COMPANY));

    ResponseEntity<Map> forgotResponse = postForgot(SUPERADMIN_EMAIL, PRIMARY_COMPANY);
    assertThat(forgotResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(deliveredTokens).hasSize(1);
    String resetToken = deliveredTokens.getFirst();

    String digest = passwordResetDigest(resetToken);
    Integer digestRowCount =
        jdbcTemplate.queryForObject(
            "select count(*) from password_reset_tokens where user_id = ? and token_digest = ? and"
                + " token is null",
            Integer.class,
            user.getId(),
            digest);
    Integer rawTokenRowCount =
        jdbcTemplate.queryForObject(
            "select count(*) from password_reset_tokens where user_id = ? and token = ?",
            Integer.class,
            user.getId(),
            resetToken);
    assertThat(digestRowCount).isEqualTo(1);
    assertThat(rawTokenRowCount).isEqualTo(0);

    ResponseEntity<Map> resetResponse = postReset(resetToken, "CanonReset123!");
    assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> loginResponse =
        rest.exchange(
            "/api/v1/auth/login",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "email", SUPERADMIN_EMAIL,
                    "password", "CanonReset123!",
                    "companyCode", PRIMARY_COMPANY),
                jsonHeaders()),
            Map.class);
    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(loginResponse.getBody()).isNotNull();
    String refreshToken = loginResponse.getBody().get("refreshToken").toString();

    ResponseEntity<Map> refreshResponse =
        rest.exchange(
            "/api/v1/auth/refresh-token",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "refreshToken", refreshToken,
                    "companyCode", PRIMARY_COMPANY),
                jsonHeaders()),
            Map.class);
    assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(refreshResponse.getBody()).isNotNull();
    String refreshedAccessToken = refreshResponse.getBody().get("accessToken").toString();

    HttpHeaders meHeaders = new HttpHeaders();
    meHeaders.setBearerAuth(refreshedAccessToken);
    meHeaders.set("X-Company-Code", PRIMARY_COMPANY);
    ResponseEntity<Map> meResponse =
        rest.exchange("/api/v1/auth/me", HttpMethod.GET, new HttpEntity<>(meHeaders), Map.class);

    assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(meResponse.getBody()).isNotNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> meData = (Map<String, Object>) meResponse.getBody().get("data");
    assertThat(meData).isNotNull();
    assertThat(meData.get("companyCode")).isEqualTo(PRIMARY_COMPANY);
    assertThat(meData.get("email")).isEqualTo(SUPERADMIN_EMAIL);
  }

  @Test
  void overlappingForgotRequests_forDifferentScopes_leaveBothResetLinksUsable() {
    List<String> deliveredTokens = Collections.synchronizedList(new ArrayList<>());
    doAnswer(
            invocation -> {
              deliveredTokens.add(invocation.getArgument(2, String.class));
              return null;
            })
        .when(emailService)
        .sendPasswordResetEmailRequired(
            eq(SUPERADMIN_EMAIL), eq("Reset Super Admin"), anyString(), anyString());

    ResponseEntity<Map> first = postForgot(SUPERADMIN_EMAIL, PRIMARY_COMPANY);
    ResponseEntity<Map> second = postForgot(SUPERADMIN_EMAIL, SECONDARY_COMPANY);

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(deliveredTokens).hasSize(2);

    ResponseEntity<Map> firstReset = postReset(deliveredTokens.getFirst(), "NewPass123!");
    ResponseEntity<Map> secondReset = postReset(deliveredTokens.getLast(), "NewPass123!");

    assertThat(firstReset.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(secondReset.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void forgotReset_forPrimaryScope_doesNotChangeSiblingScopePassword() {
    List<String> deliveredTokens = Collections.synchronizedList(new ArrayList<>());
    doAnswer(
            invocation -> {
              deliveredTokens.add(invocation.getArgument(2, String.class));
              return null;
            })
        .when(emailService)
        .sendPasswordResetEmailRequired(
            eq(SUPERADMIN_EMAIL), eq("Reset Super Admin"), anyString(), eq(PRIMARY_COMPANY));

    ResponseEntity<Map> forgotResponse = postForgot(SUPERADMIN_EMAIL, PRIMARY_COMPANY);

    assertThat(forgotResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(deliveredTokens).hasSize(1);

    ResponseEntity<Map> resetResponse = postReset(deliveredTokens.getFirst(), "PrimaryOnly123!");
    assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    assertThat(loginResponse(PRIMARY_COMPANY, "PrimaryOnly123!").getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(loginResponse(SECONDARY_COMPANY, SUPERADMIN_PASSWORD).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(loginResponse(SECONDARY_COMPANY, "PrimaryOnly123!").getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void forgotAndReset_auditTracksTargetWithoutImpersonatingAnonymousActor()
      throws InterruptedException {
    String suffix = Long.toString(System.nanoTime());
    String companyCode = "RESET-AUD-" + suffix;
    String email = "reset-audit-" + suffix + "@bbp.com";
    String password = "Passw0rd!1";
    UserAccount user =
        dataSeeder.ensureUser(email, password, "Reset Audit User", companyCode, List.of("ROLE_ADMIN"));

    List<String> deliveredTokens = Collections.synchronizedList(new ArrayList<>());
    doAnswer(
            invocation -> {
              String to = invocation.getArgument(0, String.class);
              String token = invocation.getArgument(2, String.class);
              String scope = invocation.getArgument(3, String.class);
              if (email.equalsIgnoreCase(to) && companyCode.equalsIgnoreCase(scope)) {
                deliveredTokens.add(token);
              }
              return null;
            })
        .when(emailService)
        .sendPasswordResetEmailRequired(anyString(), anyString(), anyString(), anyString());

    LocalDateTime forgotStartedAt = LocalDateTime.now().minusSeconds(1);
    ResponseEntity<Map> forgotResponse = postForgot(email, companyCode);
    assertThat(forgotResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(deliveredTokens).hasSize(1);
    AuditLog forgotAudit =
        awaitAuditEvent(AuditEvent.PASSWORD_RESET_REQUESTED, email, "forgot_password", forgotStartedAt);
    assertThat(forgotAudit.getUserId()).isEqualTo(email);
    assertThat(forgotAudit.getUserId()).isNotEqualTo(user.getPublicId().toString());
    assertThat(forgotAudit.getMetadata()).containsEntry("subjectPublicId", user.getPublicId().toString());
    assertThat(forgotAudit.getMetadata()).doesNotContainKey("actorPublicId");

    LocalDateTime resetStartedAt = LocalDateTime.now().minusSeconds(1);
    ResponseEntity<Map> resetResponse = postReset(deliveredTokens.getFirst(), "Passw0rd!2");
    assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    AuditLog resetAudit =
        awaitAuditEvent(AuditEvent.PASSWORD_RESET_COMPLETED, email, "reset_password", resetStartedAt);
    assertThat(resetAudit.getUserId()).isEqualTo(email);
    assertThat(resetAudit.getUserId()).isNotEqualTo(user.getPublicId().toString());
    assertThat(resetAudit.getMetadata()).containsEntry("subjectPublicId", user.getPublicId().toString());
    assertThat(resetAudit.getMetadata()).doesNotContainKey("actorPublicId");
  }

  private ResponseEntity<Map> postForgot(String email, String companyCode) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Company-Code", companyCode);
    Map<String, Object> payload = Map.of("email", email, "companyCode", companyCode);

    return rest.exchange(
        "/api/v1/auth/password/forgot",
        HttpMethod.POST,
        new HttpEntity<>(payload, headers),
        Map.class);
  }

  private ResponseEntity<Map> postReset(String token, String newPassword) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return rest.exchange(
        "/api/v1/auth/password/reset",
        HttpMethod.POST,
        new HttpEntity<>(
            Map.of(
                "token", token,
                "newPassword", newPassword,
                "confirmPassword", newPassword),
            headers),
        Map.class);
  }

  private HttpHeaders jsonHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  private ResponseEntity<Map> loginResponse(String companyCode, String password) {
    return rest.exchange(
        "/api/v1/auth/login",
        HttpMethod.POST,
        new HttpEntity<>(
            Map.of(
                "email", SUPERADMIN_EMAIL,
                "password", password,
                "companyCode", companyCode),
            jsonHeaders()),
        Map.class);
  }

  private String passwordResetDigest(String token) {
    return IdempotencyUtils.sha256Hex("password-reset-token:" + token);
  }

  private AuditLog awaitAuditEvent(
      AuditEvent eventType, String username, String operation, LocalDateTime notBefore)
      throws InterruptedException {
    for (int i = 0; i < 40; i++) {
      List<AuditLog> logs = auditLogRepository.findByEventTypeWithMetadataOrderByTimestampDesc(eventType);
      for (AuditLog log : logs) {
        if (log.getTimestamp() == null || log.getTimestamp().isBefore(notBefore)) {
          continue;
        }
        if (!username.equalsIgnoreCase(log.getUsername())) {
          continue;
        }
        if (log.getMetadata() == null) {
          continue;
        }
        if (operation.equals(log.getMetadata().get("operation"))) {
          return log;
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Audit event not found for " + eventType + " operation=" + operation);
  }
}
