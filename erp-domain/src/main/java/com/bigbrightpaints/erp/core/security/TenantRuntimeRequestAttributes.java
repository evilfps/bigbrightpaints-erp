package com.bigbrightpaints.erp.core.security;

public final class TenantRuntimeRequestAttributes {

    public static final String CANONICAL_ADMISSION_APPLIED =
            TenantRuntimeRequestAttributes.class.getName() + ".canonicalAdmissionApplied";

    public static final String INTERCEPTOR_FALLBACK_ADMISSION =
            TenantRuntimeRequestAttributes.class.getName() + ".interceptorFallbackAdmission";

    private TenantRuntimeRequestAttributes() {
    }
}
