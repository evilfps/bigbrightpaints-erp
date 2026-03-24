package com.bigbrightpaints.erp.modules.accounting.dto;

public enum SettlementAllocationApplication {
    DOCUMENT,
    ON_ACCOUNT,
    FUTURE_APPLICATION;

    public boolean isUnapplied() {
        return this == ON_ACCOUNT || this == FUTURE_APPLICATION;
    }
}
