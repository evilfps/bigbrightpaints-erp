package com.bigbrightpaints.erp.core.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.util.StringUtils;

/**
 * Explicit role/action matrix for the tenant-facing portal surfaces touched in the mission.
 */
public final class PortalRoleActionMatrix {

    public static final String DEALER_ONLY = "hasAuthority('ROLE_DEALER')";
    public static final String ADMIN_ONLY = "hasAuthority('ROLE_ADMIN')";
    public static final String SUPER_ADMIN_ONLY = "hasAuthority('ROLE_SUPER_ADMIN')";
    public static final String ADMIN_OR_ACCOUNTING = "hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')";
    public static final String ADMIN_SALES_ACCOUNTING = "hasAnyAuthority('ROLE_ADMIN','ROLE_SALES','ROLE_ACCOUNTING')";
    public static final String ADMIN_SALES_FACTORY_ACCOUNTING =
            "hasAnyAuthority('ROLE_ADMIN','ROLE_SALES','ROLE_FACTORY','ROLE_ACCOUNTING')";
    public static final String ADMIN_FACTORY = "hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')";
    public static final String ADMIN_FACTORY_SALES = "hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES')";
    public static final String ADMIN_ACCOUNTING_SUPER_ADMIN =
            "hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_SUPER_ADMIN')";
    public static final String FINANCIAL_DISPATCH = ADMIN_OR_ACCOUNTING + " and hasAuthority('dispatch.confirm')";
    public static final String OPERATIONAL_DISPATCH = ADMIN_FACTORY + " and hasAuthority('dispatch.confirm')";

    private static final Set<String> FINANCIAL_DISPATCH_PATHS = Set.of(
            "/api/v1/sales/dispatch/confirm",
            "/api/v1/sales/dispatch/reconcile-order-markers");

    private PortalRoleActionMatrix() {
    }

    public static String resolveAccessDeniedMessage(Authentication authentication, HttpServletRequest request) {
        if (authentication == null || request == null) {
            return null;
        }
        String path = normalizePath(request.getRequestURI());
        if (!StringUtils.hasText(path)) {
            return null;
        }
        if (FINANCIAL_DISPATCH_PATHS.contains(path)) {
            if (hasAuthority(authentication, "ROLE_FACTORY")) {
                return "Use the factory dispatch workspace to confirm the shipment. Accounting will complete the final dispatch posting.";
            }
            if (hasAuthority(authentication, "ROLE_SALES")) {
                return "Accounting must complete the final dispatch posting after the shipment is confirmed.";
            }
        }
        if (path.startsWith("/api/v1/dispatch/") && hasAuthority(authentication, "ROLE_SALES")) {
            return "Factory must complete shipment confirmation and challan details from the dispatch workspace.";
        }
        if (path.startsWith("/api/v1/credit/override-requests/")
                && (hasAuthority(authentication, "ROLE_SALES") || hasAuthority(authentication, "ROLE_FACTORY"))) {
            return "An admin or accountant must review this dispatch credit override request.";
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
