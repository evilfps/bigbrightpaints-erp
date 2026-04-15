package com.bigbrightpaints.erp.core.security;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class RequestBodyCachingFilter extends OncePerRequestFilter {

  private static final String SUPERADMIN_ROLE_MUTATION_PATH = "/api/v1/superadmin/roles";
  private static final String ROLE_MUTATION_BODY_OVERFLOW_ATTRIBUTE =
      RequestBodyCachingFilter.class.getName() + ".roleMutationBodyOverflow";
  public static final int ROLE_MUTATION_REQUEST_BODY_LIMIT_BYTES = 2048;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!shouldCacheRequestBody(request) || request instanceof ContentCachingRequestWrapper) {
      filterChain.doFilter(request, response);
      return;
    }
    ContentCachingRequestWrapper wrapper =
        new RoleMutationContentCachingRequestWrapper(
            request, ROLE_MUTATION_REQUEST_BODY_LIMIT_BYTES);
    filterChain.doFilter(wrapper, response);
  }

  public static boolean isSuperadminRoleMutationRequest(HttpServletRequest request) {
    if (request == null) {
      return false;
    }
    if (!"POST".equalsIgnoreCase(request.getMethod())) {
      return false;
    }
    return SUPERADMIN_ROLE_MUTATION_PATH.equals(normalizePath(resolveApplicationPath(request)));
  }

  public static Optional<byte[]> resolveBoundedRequestBody(HttpServletRequest request)
      throws IOException {
    if (request == null) {
      return Optional.empty();
    }
    if (isOverflowMarked(request)) {
      return Optional.empty();
    }
    ContentCachingRequestWrapper cachedRequest =
        WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
    if (cachedRequest != null) {
      if (isOverflowMarked(cachedRequest)) {
        return Optional.empty();
      }
      byte[] cachedBody = cachedRequest.getContentAsByteArray();
      if (cachedBody.length > ROLE_MUTATION_REQUEST_BODY_LIMIT_BYTES) {
        return Optional.empty();
      }
      if (cachedBody.length > 0) {
        return Optional.of(Arrays.copyOf(cachedBody, cachedBody.length));
      }
    }

    byte[] boundedBody =
        request.getInputStream().readNBytes(ROLE_MUTATION_REQUEST_BODY_LIMIT_BYTES + 1);
    if (boundedBody.length == 0 || boundedBody.length > ROLE_MUTATION_REQUEST_BODY_LIMIT_BYTES) {
      return Optional.empty();
    }
    return Optional.of(boundedBody);
  }

  public static Optional<String> resolveRequestedRole(
      HttpServletRequest request, ObjectMapper objectMapper) {
    if (request == null || objectMapper == null) {
      return Optional.empty();
    }
    try {
      Optional<byte[]> requestBody = resolveBoundedRequestBody(request);
      if (requestBody.isEmpty()) {
        return Optional.empty();
      }
      JsonNode root = objectMapper.readTree(requestBody.get());
      if (root == null) {
        return Optional.empty();
      }
      String rawRole = root.path("name").asText(null);
      if (!StringUtils.hasText(rawRole)) {
        return Optional.empty();
      }
      String normalized = rawRole.trim().toUpperCase(Locale.ROOT);
      if (!normalized.startsWith("ROLE_")) {
        normalized = "ROLE_" + normalized;
      }
      return Optional.of(normalized);
    } catch (IOException ex) {
      return Optional.empty();
    }
  }

  private boolean shouldCacheRequestBody(HttpServletRequest request) {
    return isSuperadminRoleMutationRequest(request);
  }

  private static String resolveApplicationPath(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String requestUri = request.getRequestURI();
    if (!StringUtils.hasText(requestUri)) {
      return null;
    }
    String normalizedUri = requestUri.trim();
    String contextPath = request.getContextPath();
    if (StringUtils.hasText(contextPath)) {
      String normalizedContextPath = contextPath.trim();
      if (normalizedUri.equals(normalizedContextPath)) {
        return "/";
      }
      if (normalizedUri.startsWith(normalizedContextPath + "/")) {
        normalizedUri = normalizedUri.substring(normalizedContextPath.length());
      }
    }
    return normalizedUri;
  }

  private static String normalizePath(String path) {
    if (!StringUtils.hasText(path)) {
      return path;
    }
    String normalizedPath = path.trim();
    while (normalizedPath.endsWith("/") && normalizedPath.length() > 1) {
      normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
    }
    return normalizedPath;
  }

  private static boolean isOverflowMarked(HttpServletRequest request) {
    return Boolean.TRUE.equals(request.getAttribute(ROLE_MUTATION_BODY_OVERFLOW_ATTRIBUTE));
  }

  private static final class RoleMutationContentCachingRequestWrapper
      extends ContentCachingRequestWrapper {

    private RoleMutationContentCachingRequestWrapper(
        HttpServletRequest request, int contentCacheLimit) {
      super(request, contentCacheLimit);
    }

    @Override
    protected void handleContentOverflow(int contentCacheLimit) {
      setAttribute(ROLE_MUTATION_BODY_OVERFLOW_ATTRIBUTE, Boolean.TRUE);
    }
  }
}
