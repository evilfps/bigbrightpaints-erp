package com.bigbrightpaints.erp.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;

class MustChangePasswordCorridorFilterTest {

  private final MustChangePasswordCorridorFilter filter =
      new MustChangePasswordCorridorFilter(new ObjectMapper().findAndRegisterModules());

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void retiredAdminHost_withMatrixParam_bypassesCorridorBlock() throws Exception {
    authenticateMustChangeUser();
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/admin/settings;v=1");
    request.setRequestURI("/api/v1/admin/settings;v=1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> chainCalled.set(true));

    assertThat(chainCalled).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void retiredAdminHost_withServletPathAndPathInfo_bypassesCorridorBlock() throws Exception {
    authenticateMustChangeUser();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ignored");
    request.setServletPath("/api/v1/admin");
    request.setPathInfo("/roles;v=1");
    request.setRequestURI("/ignored");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> chainCalled.set(true));

    assertThat(chainCalled).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void nonCorridorPath_isBlockedWhenPasswordChangeRequired() throws Exception {
    authenticateMustChangeUser();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/dashboard");
    request.setRequestURI("/api/v1/admin/dashboard");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> chainCalled.set(true));

    assertThat(chainCalled).isFalse();
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("PASSWORD_CHANGE_REQUIRED");
  }

  private void authenticateMustChangeUser() {
    UserAccount user =
        new UserAccount("corridor-user@bbp.com", "TENANT-A", "hash", "Corridor User");
    user.setMustChangePassword(true);
    UserPrincipal principal = new UserPrincipal(user);
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
