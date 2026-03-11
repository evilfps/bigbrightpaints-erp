package com.bigbrightpaints.erp.modules.rbac.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.modules.rbac.domain.Permission;
import com.bigbrightpaints.erp.modules.rbac.domain.PermissionRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.SystemRole;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RbacSynchronizationConfigTest {

    private static final List<String> STARTUP_EVENTS = new ArrayList<>();

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private AuditService auditService;

    @AfterEach
    void tearDown() {
        STARTUP_EVENTS.clear();
    }

    @Test
    void synchronizeSystemRoles_backfillsMissingDefaultPermissionsOnSeededRoles() {
        Map<String, Role> rolesByName = new LinkedHashMap<>();
        Role seededAdmin = role("ROLE_ADMIN", "Platform administrator");
        rolesByName.put(seededAdmin.getName(), seededAdmin);

        Map<String, Permission> permissionsByCode = new HashMap<>();

        when(roleRepository.lockByName(any())).thenAnswer(invocation -> Optional.ofNullable(rolesByName.get(invocation.getArgument(0))));
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> {
            Role saved = invocation.getArgument(0);
            rolesByName.put(saved.getName(), saved);
            return saved;
        });
        when(permissionRepository.findByCode(any())).thenAnswer(invocation -> Optional.ofNullable(permissionsByCode.get(invocation.getArgument(0))));
        when(permissionRepository.save(any(Permission.class))).thenAnswer(invocation -> {
            Permission saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", (long) permissionsByCode.size() + 1L);
            }
            permissionsByCode.put(saved.getCode(), saved);
            return saved;
        });

        RoleService roleService = new RoleService(roleRepository, permissionRepository, auditService);

        int synchronizedRoles = roleService.synchronizeSystemRoles();

        assertThat(synchronizedRoles).isGreaterThan(0);
        assertThat(seededAdmin.getPermissions())
                .extracting(Permission::getCode)
                .containsExactlyInAnyOrderElementsOf(SystemRole.ADMIN.getDefaultPermissions());
        assertThat(rolesByName.keySet()).containsAll(SystemRole.roleNameSet());
        verifyNoInteractions(auditService);
    }

    @Test
    void applicationReadySynchronization_runsAfterCommandLineRoleSeeders() {
        STARTUP_EVENTS.clear();

        try (ConfigurableApplicationContext context = new SpringApplication(TestApp.class)
                .run("--spring.main.web-application-type=none", "--spring.main.banner-mode=off")) {
            RoleService roleService = context.getBean(RoleService.class);

            assertThat(STARTUP_EVENTS).containsExactly("seed-roles", "sync-rbac");
            verify(roleService).synchronizeSystemRoles();
        }
    }

    private static Role role(String name, String description) {
        Role role = new Role();
        role.setName(name);
        role.setDescription(description);
        return role;
    }

    @Configuration(proxyBeanMethods = false)
    static class TestApp {

        @Bean
        CommandLineRunner roleSeeder() {
            return args -> STARTUP_EVENTS.add("seed-roles");
        }

        @Bean
        RoleService roleService() {
            RoleService roleService = mock(RoleService.class);
            doAnswer(invocation -> {
                STARTUP_EVENTS.add("sync-rbac");
                return 6;
            }).when(roleService).synchronizeSystemRoles();
            return roleService;
        }

        @Bean
        RbacSynchronizationConfig rbacSynchronizationConfig(RoleService roleService) {
            return new RbacSynchronizationConfig(roleService);
        }
    }
}
