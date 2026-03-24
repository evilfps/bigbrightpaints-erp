package com.bigbrightpaints.erp.modules.inventory.service;

public final class DispatchArtifactPaths {

    private DispatchArtifactPaths() {
    }

    public static String deliveryChallanNumber(String slipNumber) {
        if (slipNumber == null || slipNumber.isBlank()) {
            return null;
        }
        return "DC-" + slipNumber.trim();
    }

    public static String deliveryChallanPdfPath(Long packagingSlipId) {
        if (packagingSlipId == null) {
            return null;
        }
        return "/api/v1/dispatch/slip/" + packagingSlipId + "/challan/pdf";
    }
}
