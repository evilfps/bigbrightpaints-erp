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
        assertThat(names).containsExactlyInAnyOrder(
                "ROLE_SUPER_ADMIN",
                "ROLE_ADMIN",
                "ROLE_ACCOUNTING",
                "ROLE_FACTORY",
                "ROLE_SALES",
                "ROLE_DEALER"
        );
    }

    @Test
    void roleNameSet_containsAllRoles() {
        Set<String> names = SystemRole.roleNameSet();
        assertThat(names).containsExactlyInAnyOrder(
                "ROLE_SUPER_ADMIN",
                "ROLE_ADMIN",
                "ROLE_ACCOUNTING",
                "ROLE_FACTORY",
                "ROLE_SALES",
                "ROLE_DEALER"
        );
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
    void accountingDefaultPermissions_includeDispatchConfirmForFinancialDispatchSurface() {
        assertThat(SystemRole.ACCOUNTING.getDefaultPermissions()).contains("dispatch.confirm");
    }

    @Test
    void salesDefaultPermissions_doNotIncludeFactoryOnlyDispatchPermission() {
        assertThat(SystemRole.SALES.getDefaultPermissions()).doesNotContain("dispatch.confirm");
    }

    @Test
    void defaultPermissions_presentForAllRoles() {
        Arrays.stream(SystemRole.values())
                .map(SystemRole::getDefaultPermissions)
                .forEach(list -> assertThat(list).isNotNull());
    }
}
