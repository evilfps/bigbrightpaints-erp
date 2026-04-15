package com.bigbrightpaints.erp.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

class RequestBodyCachingFilterTest {

  @Test
  void roleMutationPathMatcher_supportsNonRootContextPaths() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/erp/api/v1/superadmin/roles");
    request.setContextPath("/erp");
    request.setRequestURI("/erp/api/v1/superadmin/roles");

    assertThat(RequestBodyCachingFilter.isSuperadminRoleMutationRequest(request)).isTrue();
  }

  @Test
  void roleMutationPathMatcher_rejectsNonMutationPaths() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/superadmin/settings");
    request.setRequestURI("/api/v1/superadmin/settings");

    assertThat(RequestBodyCachingFilter.isSuperadminRoleMutationRequest(request)).isFalse();
  }

  @Test
  void resolveBoundedRequestBody_returnsEmptyWhenPayloadExceedsLimit() throws Exception {
    String oversizedPayload =
        "{\"name\":\""
            + "R".repeat(RequestBodyCachingFilter.ROLE_MUTATION_REQUEST_BODY_LIMIT_BYTES + 64)
            + "\"}";
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/superadmin/roles");
    request.setRequestURI("/api/v1/superadmin/roles");
    request.setContent(oversizedPayload.getBytes(StandardCharsets.UTF_8));

    assertThat(RequestBodyCachingFilter.resolveBoundedRequestBody(request)).isEmpty();
  }

  @Test
  void resolveBoundedRequestBody_returnsPayloadWithinLimit() throws Exception {
    String payload = "{\"name\":\"ROLE_FACTORY\"}";
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/superadmin/roles");
    request.setRequestURI("/api/v1/superadmin/roles");
    request.setContent(payload.getBytes(StandardCharsets.UTF_8));

    assertThat(RequestBodyCachingFilter.resolveBoundedRequestBody(request))
        .isPresent()
        .contains(payload.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void resolveRequestedRole_returnsEmptyWhenWrappedRequestBodyOverflowed() throws Exception {
    String payload =
        "{\"name\":\"ROLE_FACTORY\"}"
            + " ".repeat(RequestBodyCachingFilter.ROLE_MUTATION_REQUEST_BODY_LIMIT_BYTES + 64);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/superadmin/roles");
    request.setRequestURI("/api/v1/superadmin/roles");
    request.setContent(payload.getBytes(StandardCharsets.UTF_8));

    RequestBodyCachingFilter filter = new RequestBodyCachingFilter();
    AtomicReference<Optional<String>> resolvedRole = new AtomicReference<>(Optional.empty());
    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (wrappedRequest, ignoredResponse) -> {
          wrappedRequest.getInputStream().readAllBytes();
          resolvedRole.set(
              RequestBodyCachingFilter.resolveRequestedRole(
                  (HttpServletRequest) wrappedRequest, new ObjectMapper()));
        });

    assertThat(resolvedRole.get()).isEmpty();
  }
}
