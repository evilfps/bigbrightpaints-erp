package com.bigbrightpaints.erp.core.security;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.auth.service.UserAccountDetailsService;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterRoleHierarchyTest {

    @Mock
    private JwtTokenService tokenService;

    @Mock
    private UserAccountDetailsService userDetailsService;

    @Mock
    private TokenBlacklistService blacklistService;

    @Mock
    private ObjectProvider<RoleHierarchy> roleHierarchyProvider;

    @Mock
    private FilterChain filterChain;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(tokenService, userDetailsService, blacklistService, roleHierarchyProvider);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilter_usesDirectAuthoritiesWhenRoleHierarchyIsUnavailable() throws ServletException, IOException {
        Claims claims = claims("user@bbp.com", "jti-1", Instant.parse("2026-01-01T00:00:00Z"));
        UserPrincipal principal = principalWithRole("user@bbp.com", "ROLE_SUPER_ADMIN");
        when(tokenService.parse("valid-token")).thenReturn(claims);
        when(blacklistService.isTokenBlacklisted("jti-1")).thenReturn(false);
        when(blacklistService.isUserTokenRevoked(eq("user@bbp.com"), any())).thenReturn(false);
        when(userDetailsService.loadUserByUsername("user@bbp.com")).thenReturn(principal);
        when(roleHierarchyProvider.getIfAvailable()).thenReturn(null);

        MockHttpServletRequest request = requestWithBearer("valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting(grantedAuthority -> grantedAuthority.getAuthority())
                .containsExactly("ROLE_SUPER_ADMIN");
        verify(roleHierarchyProvider).getIfAvailable();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilter_expandsAuthoritiesWhenRoleHierarchyIsAvailable() throws ServletException, IOException {
        Claims claims = claims("user@bbp.com", "jti-2", Instant.parse("2026-01-01T00:00:00Z"));
        UserPrincipal principal = principalWithRole("user@bbp.com", "ROLE_SUPER_ADMIN");
        RoleHierarchy roleHierarchy = mock(RoleHierarchy.class);
        Collection<? extends GrantedAuthority> directAuthorities = principal.getAuthorities();
        when(tokenService.parse("valid-token")).thenReturn(claims);
        when(blacklistService.isTokenBlacklisted("jti-2")).thenReturn(false);
        when(blacklistService.isUserTokenRevoked(eq("user@bbp.com"), any())).thenReturn(false);
        when(userDetailsService.loadUserByUsername("user@bbp.com")).thenReturn(principal);
        when(roleHierarchyProvider.getIfAvailable()).thenReturn(roleHierarchy);
        when(roleHierarchy.getReachableGrantedAuthorities(anyCollection()))
                .thenAnswer(invocation -> List.of(
                        new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_ADMIN")));

        MockHttpServletRequest request = requestWithBearer("valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting(grantedAuthority -> grantedAuthority.getAuthority())
                .containsExactlyInAnyOrder("ROLE_SUPER_ADMIN", "ROLE_ADMIN");
        verify(roleHierarchyProvider).getIfAvailable();
        verify(roleHierarchy).getReachableGrantedAuthorities(directAuthorities);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilter_skipsAuthenticationForLockedUser() throws ServletException, IOException {
        Claims claims = claims("user@bbp.com", "jti-3", Instant.parse("2026-01-01T00:00:00Z"));
        UserPrincipal principal = principalWithRole("user@bbp.com", "ROLE_ADMIN");
        principal.getUser().setLockedUntil(Instant.now().plusSeconds(300));
        when(tokenService.parse("valid-token")).thenReturn(claims);
        when(blacklistService.isTokenBlacklisted("jti-3")).thenReturn(false);
        when(blacklistService.isUserTokenRevoked(eq("user@bbp.com"), any())).thenReturn(false);
        when(userDetailsService.loadUserByUsername("user@bbp.com")).thenReturn(principal);

        MockHttpServletRequest request = requestWithBearer("valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    private Claims claims(String subject, String tokenId, Instant issuedAt) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(subject);
        when(claims.getId()).thenReturn(tokenId);
        when(claims.getIssuedAt()).thenReturn(Date.from(issuedAt));
        return claims;
    }

    private UserPrincipal principalWithRole(String email, String roleName) {
        UserAccount user = new UserAccount(email, "hash", "Test User");
        Role role = new Role();
        role.setName(roleName);
        user.addRole(role);
        return new UserPrincipal(user);
    }

    private MockHttpServletRequest requestWithBearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/private");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
