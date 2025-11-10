package com.bigbrightpaints.erp.orchestrator.policy;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class PolicyEnforcer {

    public void checkOrderApprovalPermissions(String userId, String companyId) {
        if (userId == null || companyId == null) {
            throw new AccessDeniedException("Missing user or company context");
        }
    }

    public void checkDispatchPermissions(String userId, String companyId) {
        if (userId == null || companyId == null) {
            throw new AccessDeniedException("Missing user or company context");
        }
    }

    public void checkPayrollPermissions(String userId, String companyId) {
        if (userId == null || companyId == null) {
            throw new AccessDeniedException("Missing user or company context");
        }
    }
}
