package com.bigbrightpaints.erp.modules.sales.dto;

public record DealerDunningHoldResponse(
    Long dealerId, boolean dunningHeld, String status, boolean alreadyOnHold) {}
