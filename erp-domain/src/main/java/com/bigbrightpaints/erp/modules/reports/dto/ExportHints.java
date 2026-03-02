package com.bigbrightpaints.erp.modules.reports.dto;

public record ExportHints(
        boolean pdfReady,
        boolean csvReady,
        String requestedFormat
) {
}
