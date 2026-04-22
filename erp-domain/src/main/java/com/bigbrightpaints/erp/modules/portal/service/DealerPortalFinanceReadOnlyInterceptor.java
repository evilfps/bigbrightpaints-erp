package com.bigbrightpaints.erp.modules.portal.service;

import java.util.Set;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.service.DealerPortalService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class DealerPortalFinanceReadOnlyInterceptor implements HandlerInterceptor {

  private static final String DEALER_PORTAL_ROOT_PATH = "/api/v1/dealer-portal";
  private static final Set<String> FINANCE_READ_ONLY_PATHS =
      Set.of(
          "/api/v1/dealer-portal/ledger",
          "/api/v1/dealer-portal/aging",
          "/api/v1/dealer-portal/invoices");

  private final DealerPortalService dealerPortalService;

  public DealerPortalFinanceReadOnlyInterceptor(DealerPortalService dealerPortalService) {
    this.dealerPortalService = dealerPortalService;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    String path = normalizePath(request != null ? request.getRequestURI() : null);
    if (!isDealerPortalPath(path)) {
      return true;
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!isDealerAuthentication(authentication)) {
      return true;
    }
    Dealer dealer = dealerPortalService.getCurrentDealer();
    if (!dealerPortalService.isFinanceReadOnlyDealer(dealer)) {
      return true;
    }
    if (isFinanceReadOnlyPath(path, request.getMethod())) {
      return true;
    }
    throw new AccessDeniedException(
        "Dealer portal access is limited to finance read-only endpoints for non-active dealers");
  }

  private boolean isDealerPortalPath(String path) {
    if (!StringUtils.hasText(path)) {
      return false;
    }
    return path.equals(DEALER_PORTAL_ROOT_PATH) || path.startsWith(DEALER_PORTAL_ROOT_PATH + "/");
  }

  private boolean isDealerAuthentication(Authentication authentication) {
    if (authentication == null || authentication.getAuthorities() == null) {
      return false;
    }
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(granted -> "ROLE_DEALER".equalsIgnoreCase(granted));
  }

  private boolean isFinanceReadOnlyPath(String path, String method) {
    if (!StringUtils.hasText(path) || !"GET".equalsIgnoreCase(method)) {
      return false;
    }
    if (FINANCE_READ_ONLY_PATHS.contains(path)) {
      return true;
    }
    return path.startsWith(DEALER_PORTAL_ROOT_PATH + "/invoices/") && path.endsWith("/pdf");
  }

  private String normalizePath(String path) {
    if (!StringUtils.hasText(path)) {
      return null;
    }
    String normalized = path.trim();
    while (normalized.endsWith("/") && normalized.length() > 1) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }
}
