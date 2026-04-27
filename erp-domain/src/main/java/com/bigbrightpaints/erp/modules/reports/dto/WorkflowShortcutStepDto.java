package com.bigbrightpaints.erp.modules.reports.dto;

public record WorkflowShortcutStepDto(
    int stepOrder, String businessStage, String method, String route, String handoffOutcome) {}
