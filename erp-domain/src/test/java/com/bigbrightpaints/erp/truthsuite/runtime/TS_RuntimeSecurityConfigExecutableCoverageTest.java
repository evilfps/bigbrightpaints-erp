package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bigbrightpaints.erp.core.security.CompanyContextFilter;
import com.bigbrightpaints.erp.core.security.JwtAuthenticationFilter;
import com.bigbrightpaints.erp.core.security.SecurityConfig;
import com.bigbrightpaints.erp.modules.auth.service.UserAccountDetailsService;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("critical")
class TS_RuntimeSecurityConfigExecutableCoverageTest {

    @Test
    void roleHierarchyAndMethodSecurityExpressionHandlerWireSuperAdminInheritance() {
        SecurityConfig securityConfig = new SecurityConfig(
                mock(JwtAuthenticationFilter.class),
                mock(CompanyContextFilter.class),
                mock(UserAccountDetailsService.class),
                false);

        RoleHierarchy roleHierarchy = securityConfig.roleHierarchy();
        assertThat(roleHierarchy).isNotNull();
        assertThat(reachableAuthorities(roleHierarchy))
                .contains("ROLE_SUPER_ADMIN", "ROLE_ADMIN");

        MethodSecurityExpressionHandler expressionHandler =
                securityConfig.methodSecurityExpressionHandler(roleHierarchy);
        assertThat(expressionHandler).isInstanceOf(DefaultMethodSecurityExpressionHandler.class);
        RoleHierarchy configuredHierarchy =
                (RoleHierarchy) ReflectionTestUtils.getField(expressionHandler, "roleHierarchy");
        assertThat(configuredHierarchy).isSameAs(roleHierarchy);
    }

    private List<String> reachableAuthorities(RoleHierarchy roleHierarchy) {
        return roleHierarchy.getReachableGrantedAuthorities(
                        List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")))
                .stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }
}
