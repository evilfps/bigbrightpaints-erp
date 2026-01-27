package com.bigbrightpaints.erp.orchestrator.policy;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PolicyEnforcerTest {

    @Test
    void checkOrderApprovalPermissions_allowsOrRejectsBasedOnContext() {
        PolicyEnforcer enforcer = new PolicyEnforcer();
        enforcer.checkOrderApprovalPermissions("user-1", "COMP");

        assertThrows(AccessDeniedException.class, () -> enforcer.checkOrderApprovalPermissions(null, "COMP"));
        assertThrows(AccessDeniedException.class, () -> enforcer.checkOrderApprovalPermissions("user-1", null));
    }

    @Test
    void checkDispatchPermissions_allowsOrRejectsBasedOnContext() {
        PolicyEnforcer enforcer = new PolicyEnforcer();
        enforcer.checkDispatchPermissions("user-1", "COMP");

        assertThrows(AccessDeniedException.class, () -> enforcer.checkDispatchPermissions(null, "COMP"));
        assertThrows(AccessDeniedException.class, () -> enforcer.checkDispatchPermissions("user-1", null));
    }

    @Test
    void checkPayrollPermissions_allowsOrRejectsBasedOnContext() {
        PolicyEnforcer enforcer = new PolicyEnforcer();
        enforcer.checkPayrollPermissions("user-1", "COMP");

        assertThrows(AccessDeniedException.class, () -> enforcer.checkPayrollPermissions(null, "COMP"));
        assertThrows(AccessDeniedException.class, () -> enforcer.checkPayrollPermissions("user-1", null));
    }
}

