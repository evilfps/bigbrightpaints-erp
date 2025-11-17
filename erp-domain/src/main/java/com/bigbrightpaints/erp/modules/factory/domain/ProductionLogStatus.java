package com.bigbrightpaints.erp.modules.factory.domain;

/**
 * Tracks the lifecycle of a production batch from mixing through packing.
 */
public enum ProductionLogStatus {
    MIXED,
    READY_TO_PACK,
    PARTIAL_PACKED,
    FULLY_PACKED;

    public boolean isPackingComplete() {
        return this == FULLY_PACKED;
    }
}
