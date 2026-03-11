package com.bigbrightpaints.erp.modules.rbac.config;

import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RbacSynchronizationConfig implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(RbacSynchronizationConfig.class);

    private final RoleService roleService;

    public RbacSynchronizationConfig(RoleService roleService) {
        this.roleService = roleService;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        int synchronizedRoles = roleService.synchronizeSystemRoles();
        if (synchronizedRoles > 0) {
            log.info("Synchronized default RBAC permissions for {} system roles after startup seeding", synchronizedRoles);
        }
    }
}
