package com.bigbrightpaints.erp.core.security;

import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Explicit role/action matrix for the tenant-facing portal surfaces touched in the mission.
 */
public final class PortalRoleActionMatrix {
  private static final String SALES_PROMOTIONS_PATH = "/api/v1/sales/promotions";

  public static final String DEALER_ONLY = "hasAuthority('ROLE_DEALER')";
  public static final String ADMIN_ONLY = "hasAuthority('ROLE_ADMIN')";
  public static final String TENANT_ADMIN_ONLY =
      "hasAuthority('ROLE_ADMIN') and !hasAuthority('ROLE_SUPER_ADMIN')";
  public static final String SUPER_ADMIN_ONLY = "hasAuthority('ROLE_SUPER_ADMIN')";
  public static final String ADMIN_OR_ACCOUNTING =
      "hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')";
  public static final String TENANT_ADMIN_OR_ACCOUNTING_ONLY =
      "hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING') and !hasAuthority('ROLE_SUPER_ADMIN')";
  public static final String ADMIN_SALES_ACCOUNTING =
      "hasAnyAuthority('ROLE_ADMIN','ROLE_SALES','ROLE_ACCOUNTING')";
  public static final String ADMIN_SALES_FACTORY_ACCOUNTING =
      "hasAnyAuthority('ROLE_ADMIN','ROLE_SALES','ROLE_FACTORY','ROLE_ACCOUNTING')";
  public static final String ADMIN_FACTORY = "hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')";
  public static final String ADMIN_FACTORY_SALES =
      "hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES')";
  public static final String FINANCIAL_DISPATCH =
      "hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING') and hasAuthority('dispatch.confirm')";
  public static final String OPERATIONAL_DISPATCH =
      "hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY') and hasAuthority('dispatch.confirm')";

  private static final Set<String> FINANCIAL_DISPATCH_PATHS =
      Set.of("/api/v1/sales/dispatch/reconcile-order-markers");

  private PortalRoleActionMatrix() {}

  public static String resolveAccessDeniedMessage(
      Authentication authentication, HttpServletRequest request) {
    if (authentication == null || request == null) {
      return null;
    }
    boolean dealer = hasAuthority(authentication, "ROLE_DEALER");
    boolean accounting = hasAuthority(authentication, "ROLE_ACCOUNTING");
    boolean sales = hasAuthority(authentication, "ROLE_SALES");
    boolean factory = hasAuthority(authentication, "ROLE_FACTORY");
    String path = normalizePath(request.getRequestURI());
    if (!StringUtils.hasText(path)) {
      return null;
    }
    if (FINANCIAL_DISPATCH_PATHS.contains(path)) {
      if (factory) {
        return "Use the factory dispatch workspace to confirm the shipment. Accounting will"
            + " complete the final dispatch posting.";
      }
      if (sales) {
        return "Accounting must complete the final dispatch posting after the shipment is"
            + " confirmed.";
      }
    }
    if (dealer && SALES_PROMOTIONS_PATH.equals(path)) {
      return "Dealer access is limited to your own portal records and exports.";
    }
    if (accounting && path.startsWith("/api/v1/dispatch/")) {
      return "Factory must complete shipment confirmation from the dispatch workspace."
          + " Accounting can reconcile downstream order markers only after dispatch is"
          + " confirmed.";
    }
    if (sales && path.startsWith("/api/v1/dispatch/")) {
      return "Factory must complete shipment confirmation and challan details from the dispatch"
          + " workspace.";
    }
    if ((sales || factory) && path.startsWith("/api/v1/credit/override-requests/")) {
      return "An admin or accountant must review this credit limit override request.";
    }
    return null;
  }

  public static String transporterOrDriverRequiredMessage() {
    return "Add the transporter name or driver name before confirming dispatch.";
  }

  public static String vehicleNumberRequiredMessage() {
    return "Add the vehicle number before confirming dispatch.";
  }

  public static String challanReferenceRequiredMessage() {
    return "Add the challan reference before confirming dispatch.";
  }

  private static boolean hasAuthority(Authentication authentication, String authority) {
    if (authentication.getAuthorities() == null) {
      return false;
    }
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(granted -> authority.equalsIgnoreCase(granted));
  }

  private static String normalizePath(String path) {
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
