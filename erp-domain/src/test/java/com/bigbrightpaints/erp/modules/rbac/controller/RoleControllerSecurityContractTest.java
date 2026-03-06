package com.bigbrightpaints.erp.modules.rbac.controller;

import com.bigbrightpaints.erp.modules.rbac.dto.CreateRoleRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

class RoleControllerSecurityContractTest {

    @Test
    void createRole_usesRoleMutationGuardAtControllerBoundary() throws Exception {
        Method method = RoleController.class.getMethod("createRole", CreateRoleRequest.class);

        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value())
                .isEqualTo("@roleService.canManageSharedRoleMutation(authentication, #request.name())");
    }
}
