package com.bigbrightpaints.erp.modules.reports.dto;

import java.util.List;

public record WorkflowShortcutCatalogDto(
    String connectedBusinessFlowModel, List<WorkflowShortcutFlowDto> workflows) {}
