package com.bigbrightpaints.erp.orchestrator.workflow;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class WorkflowService {

    private final Map<String, List<String>> workflowDefinitions = new ConcurrentHashMap<>();

    public WorkflowService() {
        workflowDefinitions.put("order-approval", List.of("VALIDATE_CREDIT", "RESERVE_STOCK", "QUEUE_PRODUCTION"));
        workflowDefinitions.put("dispatch", List.of("ALLOCATE_BATCH", "BOOK_LEDGER", "NOTIFY_CUSTOMER"));
        workflowDefinitions.put("payroll", List.of("COLLECT_EMPLOYEES", "CALCULATE", "POST_JOURNAL"));
    }

    public String startWorkflow(String workflowName) {
        if (!workflowDefinitions.containsKey(workflowName)) {
            throw new IllegalArgumentException("Unknown workflow: " + workflowName);
        }
        return UUID.randomUUID().toString();
    }

    public List<String> steps(String workflowName) {
        return workflowDefinitions.getOrDefault(workflowName, List.of());
    }
}
