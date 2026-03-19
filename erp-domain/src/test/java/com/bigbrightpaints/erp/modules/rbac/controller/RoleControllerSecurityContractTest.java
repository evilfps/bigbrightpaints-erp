package com.bigbrightpaints.erp.modules.rbac.controller;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import static org.assertj.core.api.Assertions.assertThat;

class RoleControllerSecurityContractTest {

    @Test
    void adminRolesController_doesNotExposePostMutationEndpoint() {
        assertThat(Arrays.stream(RoleController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class)
                        || exposesPostRequestMapping(method.getAnnotation(RequestMapping.class)))
                .toList())
                .isEmpty();
    }

    private boolean exposesPostRequestMapping(RequestMapping mapping) {
        if (mapping == null) {
            return false;
        }
        return Arrays.asList(mapping.method()).contains(RequestMethod.POST);
    }
}
