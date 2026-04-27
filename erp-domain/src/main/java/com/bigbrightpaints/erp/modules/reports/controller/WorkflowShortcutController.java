package com.bigbrightpaints.erp.modules.reports.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.security.SensitiveDisclosurePolicyOwner;
import com.bigbrightpaints.erp.modules.reports.dto.WorkflowShortcutCatalogDto;
import com.bigbrightpaints.erp.modules.reports.service.WorkflowShortcutService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/reports/workflow-shortcuts")
@PreAuthorize(SensitiveDisclosurePolicyOwner.REPORT_OR_ACCOUNTING_ONLY)
public class WorkflowShortcutController {

  private final WorkflowShortcutService workflowShortcutService;

  public WorkflowShortcutController(WorkflowShortcutService workflowShortcutService) {
    this.workflowShortcutService = workflowShortcutService;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<WorkflowShortcutCatalogDto>> listWorkflowShortcuts() {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Connected workflow shortcuts", workflowShortcutService.workflowShortcuts()));
  }
}
