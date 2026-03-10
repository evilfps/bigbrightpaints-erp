package com.bigbrightpaints.erp.modules.rbac.config;

import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RbacSynchronizationConfig {

    private static final Logger log = LoggerFactory.getLogger(RbacSynchronizationConfig.class);

    @Bean
    CommandLineRunner synchronizeSystemRolePermissions(RoleService roleService) {
        return args -> {
            int updatedRoles = roleService.synchronizeSystemRolePermissions();
            if (updatedRoles > 0) {
                log.info("Synchronized default permissions for {} system role(s)", updatedRoles);
            }
        };
    }
}
