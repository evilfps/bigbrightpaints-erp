package com.bigbrightpaints.erp.modules.rbac.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central definition for the platform roles we expose to admins.
 */
public enum SystemRole {
    SUPER_ADMIN("ROLE_SUPER_ADMIN", "Platform owner with global cross-tenant management and support authority", List.of(
            "portal:accounting", "portal:factory", "portal:sales", "portal:dealer",
            "dispatch.confirm", "factory.dispatch", "payroll.run"
    )),
    ADMIN("ROLE_ADMIN", "Platform administrator", List.of(
            "portal:accounting", "portal:factory", "portal:sales", "portal:dealer",
            "dispatch.confirm", "factory.dispatch", "payroll.run"
    )),
    ACCOUNTING("ROLE_ACCOUNTING", "Accounting, finance, HR, and inventory operator", List.of(
            "portal:accounting", "dispatch.confirm", "payroll.run"
    )),
    FACTORY("ROLE_FACTORY", "Factory, production, and dispatch operator", List.of(
            "portal:factory", "dispatch.confirm", "factory.dispatch"
    )),
    SALES("ROLE_SALES", "Sales operations and dealer management", List.of("portal:sales")),
    DEALER("ROLE_DEALER", "Dealer workspace user", List.of("portal:dealer"));

    private static final Map<String, SystemRole> LOOKUP = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(SystemRole::getRoleName, Function.identity()));

    private final String roleName;
    private final String description;
    private final List<String> defaultPermissions;

    SystemRole(String roleName, String description, List<String> defaultPermissions) {
        this.roleName = roleName;
        this.description = description;
        this.defaultPermissions = defaultPermissions;
    }

    public String getRoleName() {
        return roleName;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getDefaultPermissions() {
        return defaultPermissions;
    }

    public static List<String> roleNames() {
        return Arrays.stream(values())
                .map(SystemRole::getRoleName)
                .toList();
    }

    public static Set<String> roleNameSet() {
        return LOOKUP.keySet();
    }

    public static Optional<SystemRole> fromName(String rawName) {
        if (rawName == null) {
            return Optional.empty();
        }
        String normalized = rawName.trim().toUpperCase(Locale.ROOT);
        return Optional.ofNullable(LOOKUP.get(normalized));
    }
}
