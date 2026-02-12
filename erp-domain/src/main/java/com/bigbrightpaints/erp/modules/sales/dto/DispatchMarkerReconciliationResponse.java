package com.bigbrightpaints.erp.modules.sales.dto;

import java.util.List;

public record DispatchMarkerReconciliationResponse(
        int scannedOrders,
        int reconciledOrders,
        List<Long> reconciledOrderIds
) {
}
