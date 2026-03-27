package com.bigbrightpaints.erp.modules.company.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.company.domain.CompanyModule;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ModuleGatingInterceptor implements HandlerInterceptor {

  private final ModuleGatingService moduleGatingService;

  public ModuleGatingInterceptor(ModuleGatingService moduleGatingService) {
    this.moduleGatingService = moduleGatingService;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    String path = normalizePath(request != null ? request.getRequestURI() : null);
    if (!StringUtils.hasText(path) || !path.startsWith("/api/v1/")) {
      return true;
    }
    String companyCode = CompanyContextHolder.getCompanyCode();
    if (!StringUtils.hasText(companyCode)) {
      return true;
    }
    CompanyModule module = resolveTargetModule(path);
    if (module == null) {
      return true;
    }
    moduleGatingService.requireEnabledForCurrentCompany(module, path);
    return true;
  }

  CompanyModule resolveTargetModule(String path) {
    if (!StringUtils.hasText(path)) {
      return null;
    }
    if (startsWithAny(path, "/api/v1/factory", "/api/v1/production")) {
      return CompanyModule.MANUFACTURING;
    }
    if (startsWithAny(path, "/api/v1/hr", "/api/v1/payroll")) {
      return CompanyModule.HR_PAYROLL;
    }
    if (startsWithAny(path, "/api/v1/accounting/payroll")) {
      return CompanyModule.HR_PAYROLL;
    }
    if (startsWithAny(path, "/api/v1/purchasing", "/api/v1/suppliers")) {
      return CompanyModule.PURCHASING;
    }
    if (startsWithAny(path, "/api/v1/portal", "/api/v1/dealer-portal")) {
      return CompanyModule.PORTAL;
    }
    if (startsWithAny(path, "/api/v1/reports")) {
      return CompanyModule.REPORTS_ADVANCED;
    }

    if (startsWithAny(path, "/api/v1/auth")) {
      return CompanyModule.AUTH;
    }
    if (startsWithAny(path, "/api/v1/accounting", "/api/v1/invoices", "/api/v1/audit")) {
      return CompanyModule.ACCOUNTING;
    }
    if (startsWithAny(
        path,
        "/api/v1/sales",
        "/api/v1/dealers",
        "/api/v1/credit/override-requests",
        "/api/v1/credit/limit-requests")) {
      return CompanyModule.SALES;
    }
    if (startsWithAny(
        path,
        "/api/v1/inventory",
        "/api/v1/raw-materials",
        "/api/v1/dispatch",
        "/api/v1/finished-goods")) {
      return CompanyModule.INVENTORY;
    }
    return null;
  }

  private boolean startsWithAny(String path, String... prefixes) {
    for (String prefix : prefixes) {
      if (path.startsWith(prefix)) {
        return true;
      }
    }
    return false;
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
