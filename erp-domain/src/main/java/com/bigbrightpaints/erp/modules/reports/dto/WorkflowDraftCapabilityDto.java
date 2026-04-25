package com.bigbrightpaints.erp.modules.reports.dto;

public record WorkflowDraftCapabilityDto(
    String draftKey,
    String workflowKey,
    String saveMethod,
    String saveRoute,
    String resumeMethod,
    String resumeRoute,
    String promoteMethod,
    String promoteRoute,
    String pendingStatus,
    String promotedStatus,
    String sideEffectPolicy) {}
