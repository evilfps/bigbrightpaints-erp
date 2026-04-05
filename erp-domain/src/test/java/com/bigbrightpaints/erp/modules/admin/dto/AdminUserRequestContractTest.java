package com.bigbrightpaints.erp.modules.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class AdminUserRequestContractTest {

  @Test
  void createUserRequest_usesTenantScopedFieldsOnly() {
    assertThat(recordComponentNames(CreateUserRequest.class))
        .containsExactly("email", "displayName", "roles")
        .doesNotContain("companyId");
  }

  @Test
  void updateUserRequest_usesTenantScopedFieldsOnly() {
    assertThat(recordComponentNames(UpdateUserRequest.class))
        .containsExactly("displayName", "roles", "enabled")
        .doesNotContain("companyId");
  }

  private String[] recordComponentNames(Class<?> type) {
    return Arrays.stream(type.getRecordComponents())
        .map(RecordComponent::getName)
        .toArray(String[]::new);
  }
}
