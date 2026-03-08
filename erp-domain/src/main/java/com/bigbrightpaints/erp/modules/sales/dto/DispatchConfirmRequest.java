package com.bigbrightpaints.erp.modules.sales.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record DispatchConfirmRequest(
        Long packingSlipId,
        Long orderId,
        List<DispatchLine> lines,
        String dispatchNotes,
        String confirmedBy,
        Boolean adminOverrideCreditLimit,
        String overrideReason,
        Long overrideRequestId,
        String transporterName,
        String driverName,
        String vehicleNumber,
        String challanReference
) {

    public DispatchConfirmRequest(Long packingSlipId,
                                  Long orderId,
                                  List<DispatchLine> lines,
                                  String dispatchNotes,
                                  String confirmedBy,
                                  Boolean adminOverrideCreditLimit,
                                  String overrideReason,
                                  Long overrideRequestId) {
        this(packingSlipId, orderId, lines, dispatchNotes, confirmedBy, adminOverrideCreditLimit, overrideReason,
                overrideRequestId, null, null, null, null);
    }

    public record DispatchLine(
            Long lineId,
            Long batchId,
            @NotNull BigDecimal shipQty,
            BigDecimal priceOverride,
            BigDecimal discount,
            BigDecimal taxRate,
            Boolean taxInclusive,
            String notes
    ) {}
}
