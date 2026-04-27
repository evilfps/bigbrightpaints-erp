package com.bigbrightpaints.erp.modules.reports.dto;

import java.util.List;

public record WorkflowShortcutFlowDto(
    String workflowKey,
    String title,
    String operatorGuidance,
    List<WorkflowShortcutStepDto> steps,
    boolean draftCapable,
    List<WorkflowDraftCapabilityDto> draftCapabilities) {}
