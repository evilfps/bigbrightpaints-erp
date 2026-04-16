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
  void roleMutationPathMatcher_supportsServletPathAndPathInfo() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/placeholder");
    request.setServletPath("/api/v1/superadmin");
    request.setPathInfo("/roles");
    request.setRequestURI("/ignored/by/servlet-path-info");

    assertThat(RequestBodyCachingFilter.isSuperadminRoleMutationRequest(request)).isTrue();
  }

  @Test
  void roleMutationPathMatcher_supportsMatrixParamAndTrailingSlashVariants() {
    MockHttpServletRequest matrixRequest =
        new MockHttpServletRequest("POST", "/api/v1/superadmin/roles;v=1");
    matrixRequest.setRequestURI("/api/v1/superadmin/roles;v=1");
    MockHttpServletRequest trailingSlashRequest =
        new MockHttpServletRequest("POST", "/api/v1/superadmin/roles/");
    trailingSlashRequest.setRequestURI("/api/v1/superadmin/roles/");

    assertThat(RequestBodyCachingFilter.isSuperadminRoleMutationRequest(matrixRequest)).isTrue();
    assertThat(RequestBodyCachingFilter.isSuperadminRoleMutationRequest(trailingSlashRequest))
        .isTrue();
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

    HttpServletRequest wrappedRequest = wrapAndDrain(request);

    assertThat(RequestBodyCachingFilter.resolveBoundedRequestBody(wrappedRequest)).isEmpty();
  }

  @Test
  void resolveBoundedRequestBody_returnsPayloadWithinLimit() throws Exception {
    String payload = "{\"name\":\"ROLE_FACTORY\"}";
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/superadmin/roles");
    request.setRequestURI("/api/v1/superadmin/roles");
    request.setContent(payload.getBytes(StandardCharsets.UTF_8));

    HttpServletRequest wrappedRequest = wrapAndDrain(request);

    assertThat(RequestBodyCachingFilter.resolveBoundedRequestBody(wrappedRequest))
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

  @Test
  void resolveRequestedRole_returnsEmptyWhenRequestBodyIsMalformedJson() throws Exception {
    String payload = "{\"name\":";
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/superadmin/roles");
    request.setRequestURI("/api/v1/superadmin/roles");
    request.setContent(payload.getBytes(StandardCharsets.UTF_8));

    HttpServletRequest wrappedRequest = wrapAndDrain(request);

    assertThat(RequestBodyCachingFilter.resolveRequestedRole(wrappedRequest, new ObjectMapper()))
        .isEmpty();
  }

  @Test
  void resolveRequestedRole_returnsEmptyWhenNameFieldIsMissing() throws Exception {
    String payload = "{\"description\":\"missing role field\"}";
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/superadmin/roles");
    request.setRequestURI("/api/v1/superadmin/roles");
    request.setContent(payload.getBytes(StandardCharsets.UTF_8));

    HttpServletRequest wrappedRequest = wrapAndDrain(request);

    assertThat(RequestBodyCachingFilter.resolveRequestedRole(wrappedRequest, new ObjectMapper()))
        .isEmpty();
  }

  @Test
  void resolveBoundedRequestBody_returnsEmptyWhenRequestWasNotCached() throws Exception {
    String payload = "{\"name\":\"ROLE_FACTORY\"}";
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/superadmin/roles");
    request.setRequestURI("/api/v1/superadmin/roles");
    request.setContent(payload.getBytes(StandardCharsets.UTF_8));

    assertThat(RequestBodyCachingFilter.resolveBoundedRequestBody(request)).isEmpty();
  }

  private HttpServletRequest wrapAndDrain(MockHttpServletRequest request) throws Exception {
    RequestBodyCachingFilter filter = new RequestBodyCachingFilter();
    AtomicReference<HttpServletRequest> wrappedRequestRef = new AtomicReference<>();
    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (wrappedRequest, ignoredResponse) -> {
          wrappedRequestRef.set((HttpServletRequest) wrappedRequest);
          wrappedRequest.getInputStream().readAllBytes();
        });
    return wrappedRequestRef.get();
  }
}
