package com.bigbrightpaints.erp.modules.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.modules.company.domain.Company;

class UserAccountTest {

  @Test
  void prePersist_populatesMissingIdentityFields() {
    UserAccount user = new UserAccount();

    user.prePersist();

    assertThat(user.getPublicId()).isNotNull();
    assertThat(user.getCreatedAt()).isNotNull();
  }

  @Test
  void prePersist_preservesExistingIdentityFields() {
    UserAccount user = new UserAccount("user@example.com", "MOCK", "hash", "User");
    UUID publicId = user.getPublicId();
    Instant createdAt = user.getCreatedAt();

    user.prePersist();

    assertThat(user.getPublicId()).isEqualTo(publicId);
    assertThat(user.getCreatedAt()).isEqualTo(createdAt);
  }

  @Test
  void belongsToCompanyCode_requiresBoundCompanyAndCode() {
    UserAccount user = new UserAccount("user@example.com", "MOCK", "hash", "User");

    assertThat(user.belongsToCompanyCode(null)).isFalse();
    assertThat(user.belongsToCompanyCode(" ")).isFalse();

    Company company = new Company();
    company.setCode("ACME");
    user.setCompany(company);

    assertThat(user.belongsToCompanyCode("acme")).isTrue();

    company.setCode(null);
    assertThat(user.belongsToCompanyCode("acme")).isFalse();
  }

  @Test
  void recoveryCodeHelpers_handleEmptyAndPresentValues() {
    UserAccount user = new UserAccount("user@example.com", "MOCK", "hash", "User");

    assertThat(user.getMfaRecoveryCodeHashes()).isEmpty();

    user.setMfaRecoveryCodeHashes(List.of("hash-1", "hash-2"));
    assertThat(user.getMfaRecoveryCodeHashes()).containsExactly("hash-1", "hash-2");

    user.removeRecoveryCodeHash("hash-1");
    assertThat(user.getMfaRecoveryCodeHashes()).containsExactly("hash-2");

    user.removeRecoveryCodeHash("missing");
    assertThat(user.getMfaRecoveryCodeHashes()).containsExactly("hash-2");

    user.setMfaRecoveryCodeHashes(List.of());
    assertThat(ReflectionTestUtils.getField(user, "mfaRecoveryCodes")).isNull();
  }
}
