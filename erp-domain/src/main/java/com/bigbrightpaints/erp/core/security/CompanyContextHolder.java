package com.bigbrightpaints.erp.core.security;

public final class CompanyContextHolder {

  private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

  private CompanyContextHolder() {}

  public static void setCompanyCode(String companyCode) {
    CONTEXT.set(companyCode);
  }

  public static String getCompanyCode() {
    return CONTEXT.get();
  }

  public static void clear() {
    CONTEXT.remove();
  }
}
