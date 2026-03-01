package com.bigbrightpaints.erp.modules.sales.domain;

public enum DealerPaymentTerms {
    NET_30(30),
    NET_60(60),
    NET_90(90);

    private final int dueDays;

    DealerPaymentTerms(int dueDays) {
        this.dueDays = dueDays;
    }

    public int dueDays() {
        return dueDays;
    }
}
