package com.bigbrightpaints.erp.core.security;

public final class CompanyContextHolder {

    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    private CompanyContextHolder() {
    }

    public static void setCompanyId(String companyId) {
        CONTEXT.set(companyId);
    }

    public static String getCompanyId() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
