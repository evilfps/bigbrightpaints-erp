package com.bigbrightpaints.erp.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void seedSuperAdmin() {
    dataSeeder.ensureUser(
        SUPERADMIN_EMAIL,
        SUPERADMIN_PASSWORD,
        "Reset Super Admin",
        PRIMARY_COMPANY,
        List.of("ROLE_SUPER_ADMIN"));
    dataSeeder.ensureUser(
        SUPERADMIN_EMAIL,
        SUPERADMIN_PASSWORD,
        "Reset Super Admin",
        SECONDARY_COMPANY,
        List.of("ROLE_SUPER_ADMIN"));
  }

  @Test
  void forgotEndpoint_isPublicAndAntiEnumerationSafeAcrossTenantHeaders() {
    ResponseEntity<Map> knownUserResponse = postForgot(SUPERADMIN_EMAIL, "ANY-TENANT");
    ResponseEntity<Map> unknownUserResponse =
        postForgot("unknown.superadmin@bbp.com", "OTHER-TENANT");

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
  void retiredSuperAdminForgotAlias_returnsControlledCompatibilityContractAcrossTenantHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Company-Code", "ANY-TENANT");

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/auth/password/forgot/superadmin",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("email", SUPERADMIN_EMAIL), headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("success")).isEqualTo(false);
    assertThat(response.getBody().get("message"))
        .isEqualTo(
            "Deprecated super-admin forgot-password alias has been retired; use the supported"
                + " recovery routes");
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    assertThat(data).isNotNull();
    assertThat(data.get("canonicalPath")).isEqualTo("/api/v1/auth/password/forgot");
    assertThat(data.get("supportResetPath"))
        .isEqualTo("/api/v1/superadmin/tenants/{id}/support/admin-password-reset");
  }

  @Test
  void forgotEndpoint_keepsMaskedSuccessForKnownUserWhenTokenPersistenceFails() {
    doThrow(new DataAccessResourceFailureException("db unavailable"))
        .when(passwordResetTokenRepository)
        .saveAndFlush(any(PasswordResetToken.class));

    ResponseEntity<Map> knownUserResponse = postForgot(SUPERADMIN_EMAIL, "ANY-TENANT");
    ResponseEntity<Map> unknownUserResponse =
        postForgot("unknown.superadmin@bbp.com", "ANY-TENANT");

    assertThat(knownUserResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(unknownUserResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(knownUserResponse.getBody()).isNotNull();
    assertThat(unknownUserResponse.getBody()).isNotNull();
    assertThat(knownUserResponse.getBody().get("success")).isEqualTo(true);
    assertThat(knownUserResponse.getBody().get("message"))
        .isEqualTo("If the email exists, a reset link has been sent");
    assertThat(unknownUserResponse.getBody().get("success")).isEqualTo(true);
    assertThat(unknownUserResponse.getBody().get("message"))
        .isEqualTo("If the email exists, a reset link has been sent");
    verify(emailService, never())
        .sendPasswordResetEmailRequired(eq(SUPERADMIN_EMAIL), eq("Reset Super Admin"), anyString());
  }

  @Test
  void forgotEndpoint_keepsMaskedSuccessAndSkipsEmailWhenCleanupBeforeCommitFails() {
    String targetEmail = "cleanup.before.commit.user@bbp.com";
    UserAccount user =
        dataSeeder.ensureUser(
            targetEmail,
            "Admin@12345",
            "Cleanup Before Commit User",
            PRIMARY_COMPANY,
            List.of("ROLE_SUPER_ADMIN"));
    String preExistingToken = "cleanup-before-commit-preexisting-token";
    String preExistingDigest = passwordResetDigest(preExistingToken);
    passwordResetTokenRepository.saveAndFlush(
        PasswordResetToken.digestOnly(user, preExistingDigest, Instant.now().plusSeconds(600)));
    doThrow(new DataAccessResourceFailureException("cleanup unavailable"))
        .when(passwordResetTokenRepository)
        .deleteByUserAndIdNot(any(UserAccount.class), any(Long.class));

    ResponseEntity<Map> forgotResponse = postForgot(targetEmail, "ANY-TENANT");

    assertThat(forgotResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(forgotResponse.getBody()).isNotNull();
    assertThat(forgotResponse.getBody().get("success")).isEqualTo(true);
    assertThat(forgotResponse.getBody().get("message"))
        .isEqualTo("If the email exists, a reset link has been sent");
    verify(emailService, never())
        .sendPasswordResetEmailRequired(
            eq(targetEmail), eq("Cleanup Before Commit User"), anyString());

    ResponseEntity<Map> resetResponse = postReset(preExistingToken, "NewPass123!");
    assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resetResponse.getBody()).isNotNull();
    assertThat(resetResponse.getBody().get("success")).isEqualTo(true);
  }

  @Test
  void forgotEndpoint_keepsDisabledUsersMaskedWhenPersistenceFails() {
    UserAccount disabledUser =
        userAccountRepository.findByEmailIgnoreCase(SUPERADMIN_EMAIL).orElseThrow();
    disabledUser.setEnabled(false);
    userAccountRepository.saveAndFlush(disabledUser);

    doThrow(new DataAccessResourceFailureException("db unavailable"))
        .when(passwordResetTokenRepository)
        .saveAndFlush(any(PasswordResetToken.class));

    ResponseEntity<Map> disabledUserResponse = postForgot(SUPERADMIN_EMAIL, "ANY-TENANT");

    assertThat(disabledUserResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(disabledUserResponse.getBody()).isNotNull();
    assertThat(disabledUserResponse.getBody().get("success")).isEqualTo(true);
    assertThat(disabledUserResponse.getBody().get("message"))
        .isEqualTo("If the email exists, a reset link has been sent");
  }

  @Test
  void forgotPersistenceFailure_keepsPreExistingResetTokenUsableAndDoesNotDispatchEmail() {
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

    ResponseEntity<Map> forgotResponse = postForgot(targetEmail, "ANY-TENANT");

    assertThat(forgotResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(forgotResponse.getBody()).isNotNull();
    assertThat(forgotResponse.getBody().get("success")).isEqualTo(true);
    assertThat(forgotResponse.getBody().get("message"))
        .isEqualTo("If the email exists, a reset link has been sent");
    verify(emailService, never())
        .sendPasswordResetEmailRequired(eq(targetEmail), eq("Preexisting Reset User"), anyString());

    ResponseEntity<Map> resetResponse = postReset(preExistingToken, "NewPass123!");
    assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resetResponse.getBody()).isNotNull();
    assertThat(resetResponse.getBody().get("success")).isEqualTo(true);
  }

  @Test
  void forgotEndpoint_keepsMaskedSuccessForKnownUserWhenUnexpectedRuntimeOccurs() {
    doThrow(new IllegalStateException("unexpected ordering failure"))
        .when(passwordResetTokenRepository)
        .touchCreatedAt(anyLong(), any(Instant.class));

    ResponseEntity<Map> forgotResponse = postForgot(SUPERADMIN_EMAIL, "ANY-TENANT");

    assertThat(forgotResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(forgotResponse.getBody()).isNotNull();
    assertThat(forgotResponse.getBody().get("success")).isEqualTo(true);
    assertThat(forgotResponse.getBody().get("message"))
        .isEqualTo("If the email exists, a reset link has been sent");
    verify(emailService, never())
        .sendPasswordResetEmailRequired(eq(SUPERADMIN_EMAIL), eq("Reset Super Admin"), anyString());
  }

  @Test
  void
      forgotCleanupFailure_afterDispatchFailure_doesNotRestorePriorTokenWhenIssuedTokenCleanupStateIsUnknown() {
    String targetEmail = "cleanup.failure.reset.user@bbp.com";
    UserAccount user =
        dataSeeder.ensureUser(
            targetEmail,
            "Admin@12345",
            "Cleanup Failure User",
            PRIMARY_COMPANY,
            List.of("ROLE_SUPER_ADMIN"));
    String preExistingToken = "cleanup-preexisting-reset-token";
    String preExistingDigest = passwordResetDigest(preExistingToken);
    passwordResetTokenRepository.saveAndFlush(
        PasswordResetToken.digestOnly(user, preExistingDigest, Instant.now().plusSeconds(600)));

    doThrow(new RuntimeException("smtp down"))
        .when(emailService)
        .sendPasswordResetEmailRequired(eq(targetEmail), eq("Cleanup Failure User"), anyString());
    doThrow(new DataAccessResourceFailureException("cleanup unavailable"))
        .when(passwordResetTokenRepository)
        .deleteByTokenDigest(anyString());

    ResponseEntity<Map> forgotResponse = postForgot(targetEmail, "ANY-TENANT");

    assertThat(forgotResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(forgotResponse.getBody()).isNotNull();
    assertThat(forgotResponse.getBody().get("success")).isEqualTo(true);
    assertThat(forgotResponse.getBody().get("message"))
        .isEqualTo("If the email exists, a reset link has been sent");

    Integer tokenCount =
        jdbcTemplate.queryForObject(
            "select count(*) from password_reset_tokens where user_id = ?",
            Integer.class,
            user.getId());
    Integer priorDigestCount =
        jdbcTemplate.queryForObject(
            "select count(*) from password_reset_tokens where user_id = ? and token_digest = ?",
            Integer.class,
            user.getId(),
            preExistingDigest);
    assertThat(tokenCount).isGreaterThanOrEqualTo(1);
    assertThat(priorDigestCount).isZero();

    ResponseEntity<Map> resetResponse = postReset(preExistingToken, "NewPass123!");
    assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(resetResponse.getBody()).isNotNull();
    assertThat(resetResponse.getBody().get("message")).isEqualTo("Invalid or expired token");
  }

  @Test
  void forgotCleanupFailure_restoresLastDeliveredTokenNotNewestUndeliveredPriorToken() {
    String targetEmail = "cleanup.failure.delivered.prior.user@bbp.com";
    UserAccount user =
        dataSeeder.ensureUser(
            targetEmail,
            "Admin@12345",
            "Cleanup Delivered Prior User",
            PRIMARY_COMPANY,
            List.of("ROLE_SUPER_ADMIN"));

    String deliveredPriorToken = "cleanup-delivered-prior-token";
    String deliveredPriorDigest = passwordResetDigest(deliveredPriorToken);
    PasswordResetToken deliveredPrior =
        passwordResetTokenRepository.saveAndFlush(
            PasswordResetToken.digestOnly(
                user, deliveredPriorDigest, Instant.now().plusSeconds(600)));
    Instant deliveredAt = Instant.now().minusSeconds(180);
    Instant deliveredCreatedAt = Instant.now().minusSeconds(180);
    jdbcTemplate.update(
        "update password_reset_tokens set delivered_at = ?, created_at = ? where id = ?",
        Timestamp.from(deliveredAt),
        Timestamp.from(deliveredCreatedAt),
        deliveredPrior.getId());

    String undeliveredPriorToken = "cleanup-undelivered-prior-token";
    String undeliveredPriorDigest = passwordResetDigest(undeliveredPriorToken);
    PasswordResetToken undeliveredPrior =
        passwordResetTokenRepository.saveAndFlush(
            PasswordResetToken.digestOnly(
                user, undeliveredPriorDigest, Instant.now().plusSeconds(600)));
    Instant undeliveredCreatedAt = Instant.now().minusSeconds(30);
    jdbcTemplate.update(
        "update password_reset_tokens set created_at = ? where id = ?",
        Timestamp.from(undeliveredCreatedAt),
        undeliveredPrior.getId());

    doThrow(new RuntimeException("smtp down"))
        .when(emailService)
        .sendPasswordResetEmailRequired(
            eq(targetEmail), eq("Cleanup Delivered Prior User"), anyString());

    ResponseEntity<Map> forgotResponse = postForgot(targetEmail, "ANY-TENANT");

    assertThat(forgotResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(forgotResponse.getBody()).isNotNull();
    assertThat(forgotResponse.getBody().get("success")).isEqualTo(true);
    assertThat(forgotResponse.getBody().get("message"))
        .isEqualTo("If the email exists, a reset link has been sent");

    Integer activeTokenCount =
        jdbcTemplate.queryForObject(
            "select count(*) from password_reset_tokens where user_id = ?",
            Integer.class,
            user.getId());
    Integer deliveredPriorCount =
        jdbcTemplate.queryForObject(
            "select count(*) from password_reset_tokens where user_id = ? and token_digest = ?",
            Integer.class,
            user.getId(),
            deliveredPriorDigest);
    Integer deliveredPriorMarkedCount =
        jdbcTemplate.queryForObject(
            "select count(*) from password_reset_tokens where user_id = ? and token_digest = ? and"
                + " delivered_at is not null",
            Integer.class,
            user.getId(),
            deliveredPriorDigest);
    Integer undeliveredPriorCount =
        jdbcTemplate.queryForObject(
            "select count(*) from password_reset_tokens where user_id = ? and token_digest = ?",
            Integer.class,
            user.getId(),
            undeliveredPriorDigest);
    assertThat(activeTokenCount).isEqualTo(1);
    assertThat(deliveredPriorCount).isEqualTo(1);
    assertThat(deliveredPriorMarkedCount).isEqualTo(1);
    assertThat(undeliveredPriorCount).isZero();

    ResponseEntity<Map> deliveredResetResponse = postReset(deliveredPriorToken, "NewPass123!");
    assertThat(deliveredResetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(deliveredResetResponse.getBody()).isNotNull();
    assertThat(deliveredResetResponse.getBody().get("success")).isEqualTo(true);

    ResponseEntity<Map> undeliveredResetResponse = postReset(undeliveredPriorToken, "NewPass123!");
    assertThat(undeliveredResetResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(undeliveredResetResponse.getBody()).isNotNull();
    assertThat(undeliveredResetResponse.getBody().get("message"))
        .isEqualTo("Invalid or expired token");
  }

  @Test
  void forgotDeliveryMarkerFailure_restoresLastDeliveredTokenAndInvalidatesNewToken() {
    String targetEmail = "delivery.marker.failure.user@bbp.com";
    UserAccount user =
        dataSeeder.ensureUser(
            targetEmail,
            "Admin@12345",
            "Delivery Marker Failure User",
            PRIMARY_COMPANY,
            List.of("ROLE_SUPER_ADMIN"));

    String deliveredPriorToken = "delivery-marker-prior-token";
    String deliveredPriorDigest = passwordResetDigest(deliveredPriorToken);
    PasswordResetToken deliveredPrior =
        passwordResetTokenRepository.saveAndFlush(
            PasswordResetToken.digestOnly(
                user, deliveredPriorDigest, Instant.now().plusSeconds(600)));
    Instant deliveredAt = Instant.now().minusSeconds(180);
    Instant deliveredCreatedAt = Instant.now().minusSeconds(180);
    jdbcTemplate.update(
        "update password_reset_tokens set delivered_at = ?, created_at = ? where id = ?",
        Timestamp.from(deliveredAt),
        Timestamp.from(deliveredCreatedAt),
        deliveredPrior.getId());

    List<String> deliveredTokens = Collections.synchronizedList(new ArrayList<>());
    doAnswer(
            invocation -> {
              deliveredTokens.add(invocation.getArgument(2, String.class));
              return null;
            })
        .when(emailService)
        .sendPasswordResetEmailRequired(
            eq(targetEmail), eq("Delivery Marker Failure User"), anyString());
    doReturn(0).when(passwordResetTokenRepository).markDeliveredAt(anyLong(), any(Instant.class));

    ResponseEntity<Map> forgotResponse = postForgot(targetEmail, "ANY-TENANT");

    assertThat(forgotResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(forgotResponse.getBody()).isNotNull();
    assertThat(forgotResponse.getBody().get("success")).isEqualTo(true);
    assertThat(deliveredTokens).hasSize(1);

    Integer activeTokenCount =
        jdbcTemplate.queryForObject(
            "select count(*) from password_reset_tokens where user_id = ?",
            Integer.class,
            user.getId());
    Integer deliveredPriorCount =
        jdbcTemplate.queryForObject(
            "select count(*) from password_reset_tokens where user_id = ? and token_digest = ?",
            Integer.class,
            user.getId(),
            deliveredPriorDigest);
    Integer deliveredPriorMarkedCount =
        jdbcTemplate.queryForObject(
            "select count(*) from password_reset_tokens where user_id = ? and token_digest = ? and"
                + " delivered_at is not null",
            Integer.class,
            user.getId(),
            deliveredPriorDigest);
    assertThat(activeTokenCount).isEqualTo(1);
    assertThat(deliveredPriorCount).isEqualTo(1);
    assertThat(deliveredPriorMarkedCount).isEqualTo(1);

    ResponseEntity<Map> newResetResponse = postReset(deliveredTokens.getFirst(), "NewPass123!");
    assertThat(newResetResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(newResetResponse.getBody()).isNotNull();
    assertThat(newResetResponse.getBody().get("message")).isEqualTo("Invalid or expired token");

    ResponseEntity<Map> priorResetResponse = postReset(deliveredPriorToken, "NewPass123!");
    assertThat(priorResetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(priorResetResponse.getBody()).isNotNull();
    assertThat(priorResetResponse.getBody().get("success")).isEqualTo(true);
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
    UserAccount user = userAccountRepository.findByEmailIgnoreCase(SUPERADMIN_EMAIL).orElseThrow();
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
        .sendPasswordResetEmailRequired(eq(SUPERADMIN_EMAIL), eq("Reset Super Admin"), anyString());

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
  void overlappingForgotRequests_leaveLatestResetLinkUsable() throws Exception {
    CountDownLatch bothEmailsQueued = new CountDownLatch(2);
    List<String> deliveredTokens = Collections.synchronizedList(new ArrayList<>());
    doAnswer(
            invocation -> {
              deliveredTokens.add(invocation.getArgument(2, String.class));
              bothEmailsQueued.countDown();
              assertThat(bothEmailsQueued.await(5, TimeUnit.SECONDS)).isTrue();
              return null;
            })
        .when(emailService)
        .sendPasswordResetEmailRequired(eq(SUPERADMIN_EMAIL), eq("Reset Super Admin"), anyString());

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<ResponseEntity<Map>> first =
          executor.submit(() -> postForgot(SUPERADMIN_EMAIL, "TENANT-A"));
      Future<ResponseEntity<Map>> second =
          executor.submit(() -> postForgot(SUPERADMIN_EMAIL, "TENANT-B"));

      assertThat(first.get(5, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(second.get(5, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
    } finally {
      executor.shutdownNow();
    }

    assertThat(deliveredTokens).hasSize(2);
    ResponseEntity<Map> firstReset = postReset(deliveredTokens.getFirst(), "NewPass123!");
    ResponseEntity<Map> secondReset = postReset(deliveredTokens.getLast(), "NewPass123!");

    long successfulResets =
        List.of(firstReset, secondReset).stream()
            .filter(response -> response.getStatusCode() == HttpStatus.OK)
            .count();
    long failedResets =
        List.of(firstReset, secondReset).stream()
            .filter(response -> response.getStatusCode() == HttpStatus.BAD_REQUEST)
            .count();

    assertThat(successfulResets).isEqualTo(1);
    assertThat(failedResets).isEqualTo(1);
  }

  private ResponseEntity<Map> postForgot(String email, String companyCodeHeader) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Company-Code", companyCodeHeader);
    Map<String, Object> payload = Map.of("email", email);

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

  private String passwordResetDigest(String token) {
    return IdempotencyUtils.sha256Hex("password-reset-token:" + token);
  }
}
