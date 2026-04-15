package com.bigbrightpaints.erp.core.security;

/**
 * Canonical retired tenant-admin hosts that intentionally fall through to dispatcher 404.
 */
public final class RetiredTenantAdminHostPaths {

  public static final String ADMIN_SETTINGS = "/api/v1/admin/settings";
  public static final String ADMIN_SETTINGS_WILDCARD = "/api/v1/admin/settings/**";
  public static final String ADMIN_ROLES = "/api/v1/admin/roles";
  public static final String ADMIN_ROLES_WILDCARD = "/api/v1/admin/roles/**";

  private static final String[] REQUEST_MATCHERS = {
    ADMIN_SETTINGS, ADMIN_SETTINGS_WILDCARD, ADMIN_ROLES, ADMIN_ROLES_WILDCARD
  };

  private RetiredTenantAdminHostPaths() {}

  public static boolean matchesNormalizedPath(String normalizedPath) {
    if (normalizedPath == null || normalizedPath.isBlank()) {
      return false;
    }
    return normalizedPath.equals(ADMIN_SETTINGS)
        || normalizedPath.startsWith(ADMIN_SETTINGS + "/")
        || normalizedPath.equals(ADMIN_ROLES)
        || normalizedPath.startsWith(ADMIN_ROLES + "/");
  }

  public static String[] requestMatchers() {
    return REQUEST_MATCHERS.clone();
  }
}
