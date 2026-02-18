package com.bigbrightpaints.erp.modules.invoice.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceControllerSecurityContractTest {

    @Test
    void downloadInvoicePdf_requiresAdminRole() throws NoSuchMethodException {
        Method method = InvoiceController.class.getMethod("downloadInvoicePdf", Long.class);
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAuthority('ROLE_ADMIN')");
    }
}
