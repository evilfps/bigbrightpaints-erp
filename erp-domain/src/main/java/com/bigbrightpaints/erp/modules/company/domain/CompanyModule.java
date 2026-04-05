package com.bigbrightpaints.erp.modules.company.domain;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.util.StringUtils;

public enum CompanyModule {
  AUTH(false),
  ACCOUNTING(false),
  SALES(false),
  INVENTORY(false),
  MANUFACTURING(true),
  HR_PAYROLL(true),
  PURCHASING(true),
  PORTAL(true),
  REPORTS_ADVANCED(true);

  private static final EnumSet<CompanyModule> GATABLE_MODULES =
      EnumSet.of(MANUFACTURING, HR_PAYROLL, PURCHASING, PORTAL, REPORTS_ADVANCED);

  private static final EnumSet<CompanyModule> DEFAULT_ENABLED_GATABLE_MODULES =
      EnumSet.of(MANUFACTURING, PURCHASING, PORTAL, REPORTS_ADVANCED);

  private final boolean gatable;

  CompanyModule(boolean gatable) {
    this.gatable = gatable;
  }

  public boolean isGatable() {
    return gatable;
  }

  public boolean isCore() {
    return !gatable;
  }

  public static Optional<CompanyModule> fromValue(String rawValue) {
    if (!StringUtils.hasText(rawValue)) {
      return Optional.empty();
    }
    String normalized = rawValue.trim().toUpperCase(Locale.ROOT);
    for (CompanyModule module : values()) {
      if (module.name().equals(normalized)) {
        return Optional.of(module);
      }
    }
    return Optional.empty();
  }

  public static Set<String> defaultEnabledGatableModuleNames() {
    LinkedHashSet<String> moduleNames = new LinkedHashSet<>();
    for (CompanyModule module : DEFAULT_ENABLED_GATABLE_MODULES) {
      moduleNames.add(module.name());
    }
    return moduleNames;
  }

  public static Set<String> normalizeEnabledGatableModuleNames(Set<String> rawModuleNames) {
    if (rawModuleNames == null) {
      return defaultEnabledGatableModuleNames();
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String moduleName : rawModuleNames) {
      fromValue(moduleName)
          .filter(CompanyModule::isGatable)
          .map(CompanyModule::name)
          .ifPresent(normalized::add);
    }
    return normalized;
  }
}
