package com.bigbrightpaints.erp.modules.auth.domain;

import com.bigbrightpaints.erp.modules.rbac.domain.Permission;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserPrincipalTest {

    @Test
    void authorities_include_role_names_and_permissions() {
        Role role = new Role();
        role.setName("ROLE_FACTORY");

        Permission dispatchConfirm = new Permission();
        dispatchConfirm.setCode("dispatch.confirm");

        Permission portalFactory = new Permission();
        portalFactory.setCode("portal:factory");

        role.getPermissions().add(dispatchConfirm);
        role.getPermissions().add(portalFactory);

        UserAccount user = new UserAccount("worker@bbp.test", "hash", "Worker");
        user.getRoles().add(role);

        UserPrincipal principal = new UserPrincipal(user);
        List<String> authorities = principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        assertThat(authorities)
                .contains("ROLE_FACTORY", "dispatch.confirm", "portal:factory")
                .doesNotHaveDuplicates();
    }
}
