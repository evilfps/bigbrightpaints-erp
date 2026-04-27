package com.bigbrightpaints.erp.core.security;

import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Request-scoped marker that prevents duplicate ACCESS_DENIED audits across service and fallback
 * layers in a single request.
 */
public final class AccessDeniedAuditMarker {

  private static final String ATTRIBUTE_ALREADY_AUDITED =
      AccessDeniedAuditMarker.class.getName() + ".alreadyAudited";

  private AccessDeniedAuditMarker() {}

  public static boolean isCurrentRequestAlreadyAudited(HttpServletRequest request) {
    return request != null && request.getAttribute(ATTRIBUTE_ALREADY_AUDITED) != null;
  }

  public static void markCurrentRequestAudited() {
    ServletRequestAttributes attrs =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attrs == null) {
      return;
    }
    HttpServletRequest request = attrs.getRequest();
    if (request == null) {
      return;
    }
    request.setAttribute(ATTRIBUTE_ALREADY_AUDITED, Boolean.TRUE);
  }

  public static String resolveTenantScope(HttpServletRequest request) {
    String boundScope = CompanyContextHolder.getCompanyCode();
    if (StringUtils.hasText(boundScope)) {
      return boundScope.trim();
    }
    if (request != null) {
      Object claimsAttr = request.getAttribute("jwtClaims");
      if (claimsAttr instanceof Claims claims) {
        String tokenScope = claims.get("companyCode", String.class);
        if (StringUtils.hasText(tokenScope)) {
          return tokenScope.trim();
        }
      }
    }
    return null;
  }
}
