package com.bigbrightpaints.erp.core.security;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class MustChangePasswordCorridorFilter extends OncePerRequestFilter {

  private static final String PASSWORD_CHANGE_REQUIRED_MESSAGE =
      "Password change required before accessing this resource";
  private static final Set<String> READ_ONLY_CORRIDOR_PATHS = Set.of("/api/v1/auth/me");
  private static final Set<String> MUTATING_CORRIDOR_PATHS =
      Set.of("/api/v1/auth/password/change", "/api/v1/auth/logout", "/api/v1/auth/refresh-token");
  private static final Set<String> RETIRED_AUTH_SURFACES = Set.of("/api/v1/auth/profile");

  private final ObjectMapper objectMapper;

  public MustChangePasswordCorridorFilter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!requiresPasswordChange() || isCorridorRequestAllowed(request)) {
      filterChain.doFilter(request, response);
      return;
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("code", ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS.getCode());
    data.put("reason", "PASSWORD_CHANGE_REQUIRED");
    data.put("mustChangePassword", true);

    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(
        response.getWriter(), ApiResponse.failure(PASSWORD_CHANGE_REQUIRED_MESSAGE, data));
  }

  private boolean requiresPasswordChange() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      return false;
    }
    Object principal = authentication.getPrincipal();
    return principal instanceof UserPrincipal userPrincipal
        && userPrincipal.getUser() != null
        && userPrincipal.getUser().isMustChangePassword();
  }

  private boolean isCorridorRequestAllowed(HttpServletRequest request) {
    if (request == null) {
      return false;
    }
    if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
      return true;
    }

    String normalizedPath = normalizePath(resolveApplicationPath(request));
    if (!StringUtils.hasText(normalizedPath)) {
      return false;
    }
    if (RETIRED_AUTH_SURFACES.contains(normalizedPath)) {
      return true;
    }

    String method = request.getMethod();
    if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
      return READ_ONLY_CORRIDOR_PATHS.contains(normalizedPath);
    }
    if ("POST".equalsIgnoreCase(method)) {
      return MUTATING_CORRIDOR_PATHS.contains(normalizedPath);
    }
    return false;
  }

  private String resolveApplicationPath(HttpServletRequest request) {
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

  private String normalizePath(String path) {
    if (!StringUtils.hasText(path)) {
      return path;
    }
    String normalizedPath = path.trim();
    while (normalizedPath.endsWith("/") && normalizedPath.length() > 1) {
      normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
    }
    return normalizedPath;
  }
}
