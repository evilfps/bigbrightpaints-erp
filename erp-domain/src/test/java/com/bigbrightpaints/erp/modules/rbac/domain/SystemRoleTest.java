package com.bigbrightpaints.erp.modules.rbac.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SystemRoleTest {

  @Test
  void roleNames_containsAllRoles() {
    List<String> names = SystemRole.roleNames();
    assertThat(names)
        .containsExactlyInAnyOrder(
            "ROLE_SUPER_ADMIN",
            "ROLE_ADMIN",
            "ROLE_ACCOUNTING",
            "ROLE_FACTORY",
            "ROLE_SALES",
            "ROLE_DEALER");
  }

  @Test
  void roleNameSet_containsAllRoles() {
    Set<String> names = SystemRole.roleNameSet();
    assertThat(names)
        .containsExactlyInAnyOrder(
            "ROLE_SUPER_ADMIN",
            "ROLE_ADMIN",
            "ROLE_ACCOUNTING",
            "ROLE_FACTORY",
            "ROLE_SALES",
            "ROLE_DEALER");
  }

  @Test
  void roleNameSet_sizeMatchesEnum() {
    assertThat(SystemRole.roleNameSet()).hasSize(SystemRole.values().length);
  }

  @Test
  void fromName_null_isEmpty() {
    assertThat(SystemRole.fromName(null)).isEmpty();
  }

  @Test
  void fromName_trimmedAndUppercase_matchesRole() {
    assertThat(SystemRole.fromName(" role_admin ")).contains(SystemRole.ADMIN);
  }

  @Test
  void fromName_unknown_isEmpty() {
    assertThat(SystemRole.fromName("ROLE_UNKNOWN")).isEmpty();
  }

  @Test
  void adminDefaultPermissions_includeDispatchConfirm() {
    assertThat(SystemRole.ADMIN.getDefaultPermissions()).contains("dispatch.confirm");
  }

  @Test
  void accountingAndFactoryDefaultPermissions_includeDispatchConfirmForCanonicalDispatchSurface() {
    assertThat(SystemRole.ACCOUNTING.getDefaultPermissions()).contains("dispatch.confirm");
    assertThat(SystemRole.FACTORY.getDefaultPermissions()).contains("dispatch.confirm");
  }

  @Test
  void salesDefaults_doNotIncludeDispatchConfirm() {
    assertThat(SystemRole.SALES.getDefaultPermissions()).doesNotContain("dispatch.confirm");
  }

  @Test
  void retiredPermissions_pruneDispatchConfirmFromSalesOnly() {
    assertThat(SystemRole.SALES.getRetiredPermissions()).containsExactly("dispatch.confirm");
    assertThat(SystemRole.ACCOUNTING.getRetiredPermissions()).isEmpty();
    assertThat(SystemRole.FACTORY.getRetiredPermissions()).isEmpty();
    assertThat(SystemRole.ADMIN.getRetiredPermissions()).isEmpty();
    assertThat(SystemRole.SUPER_ADMIN.getRetiredPermissions()).isEmpty();
  }

  @Test
  void defaultPermissions_presentForAllRoles() {
    Arrays.stream(SystemRole.values())
        .map(SystemRole::getDefaultPermissions)
        .forEach(list -> assertThat(list).isNotNull());
  }
}
