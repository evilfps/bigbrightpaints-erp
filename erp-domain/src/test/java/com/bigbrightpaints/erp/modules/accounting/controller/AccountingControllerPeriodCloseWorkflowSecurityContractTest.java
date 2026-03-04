package com.bigbrightpaints.erp.modules.accounting.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class AccountingControllerPeriodCloseWorkflowSecurityContractTest {

    @Test
    void reopenPeriod_requiresSuperAdminAuthorityAnnotation() throws NoSuchMethodException {
        Method method = AccountingController.class.getMethod(
                "reopenPeriod",
                Long.class,
                com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest.class);

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAuthority('ROLE_SUPER_ADMIN')");
    }
}
