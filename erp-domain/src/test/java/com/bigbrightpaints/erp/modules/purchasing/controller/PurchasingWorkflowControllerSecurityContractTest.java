package com.bigbrightpaints.erp.modules.purchasing.controller;

import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderVoidRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class PurchasingWorkflowControllerSecurityContractTest {

    @Test
    void workflowMutationEndpointsRequireAdminOrAccountingRole() throws Exception {
        assertPreAuthorize(
                PurchasingWorkflowController.class.getMethod("createPurchaseOrder", PurchaseOrderRequest.class),
                "hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')");
        assertPreAuthorize(
                PurchasingWorkflowController.class.getMethod(
                        "createGoodsReceipt",
                        String.class,
                        String.class,
                        GoodsReceiptRequest.class),
                "hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')");
        assertPreAuthorize(
                PurchasingWorkflowController.class.getMethod("approvePurchaseOrder", Long.class),
                "hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')");
        assertPreAuthorize(
                PurchasingWorkflowController.class.getMethod("voidPurchaseOrder", Long.class, PurchaseOrderVoidRequest.class),
                "hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')");
        assertPreAuthorize(
                PurchasingWorkflowController.class.getMethod("closePurchaseOrder", Long.class),
                "hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')");
    }

    private void assertPreAuthorize(Method method, String expectedExpression) {
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo(expectedExpression);
    }
}
