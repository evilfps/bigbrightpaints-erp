package com.bigbrightpaints.erp.modules.sales.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitOverrideDecisionRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class CreditLimitOverrideControllerSecurityContractTest {

    @Test
    void approveRequest_requiresAdminOrAccountingAuthorityAnnotation() throws NoSuchMethodException {
        Method method = CreditLimitOverrideController.class.getMethod(
                "approveRequest",
                Long.class,
                CreditLimitOverrideDecisionRequest.class,
                java.security.Principal.class);

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')");
    }

    @Test
    void rejectRequest_requiresAdminOrAccountingAuthorityAnnotation() throws NoSuchMethodException {
        Method method = CreditLimitOverrideController.class.getMethod(
                "rejectRequest",
                Long.class,
                CreditLimitOverrideDecisionRequest.class,
                java.security.Principal.class);

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')");
    }
}
