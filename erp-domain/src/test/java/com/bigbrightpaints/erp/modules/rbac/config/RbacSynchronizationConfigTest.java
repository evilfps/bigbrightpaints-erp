package com.bigbrightpaints.erp.modules.rbac.config;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.CommandLineRunner;

@ExtendWith(MockitoExtension.class)
class RbacSynchronizationConfigTest {

    @Mock
    private RoleService roleService;

    @Test
    void synchronizeSystemRolePermissions_runnerInvokesRoleSyncWhenUpdatesExist() throws Exception {
        when(roleService.synchronizeSystemRolePermissions()).thenReturn(2);

        CommandLineRunner runner = new RbacSynchronizationConfig().synchronizeSystemRolePermissions(roleService);
        runner.run("--sync");

        verify(roleService).synchronizeSystemRolePermissions();
    }

    @Test
    void synchronizeSystemRolePermissions_runnerInvokesRoleSyncWhenNothingChanges() throws Exception {
        when(roleService.synchronizeSystemRolePermissions()).thenReturn(0);

        CommandLineRunner runner = new RbacSynchronizationConfig().synchronizeSystemRolePermissions(roleService);
        runner.run();

        verify(roleService).synchronizeSystemRolePermissions();
    }
}
