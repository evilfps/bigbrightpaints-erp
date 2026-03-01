package com.bigbrightpaints.erp.modules.sales.dto;

import com.bigbrightpaints.erp.modules.accounting.domain.GstRegistrationType;
import com.bigbrightpaints.erp.modules.sales.domain.DealerPaymentTerms;
import java.math.BigDecimal;
import java.util.UUID;

public record DealerLookupResponse(
        Long id,
        UUID publicId,
        String name,
        String code,
        BigDecimal outstandingBalance,
        BigDecimal creditLimit,
        Long receivableAccountId,
        String receivableAccountCode,
        String stateCode,
        GstRegistrationType gstRegistrationType,
        DealerPaymentTerms paymentTerms,
        String region,
        String creditStatus
) {

    public DealerLookupResponse(Long id,
                                UUID publicId,
                                String name,
                                String code,
                                BigDecimal outstandingBalance,
                                BigDecimal creditLimit,
                                Long receivableAccountId,
                                String receivableAccountCode) {
        this(id, publicId, name, code, outstandingBalance, creditLimit, receivableAccountId,
                receivableAccountCode, null, GstRegistrationType.UNREGISTERED,
                DealerPaymentTerms.NET_30, null, "WITHIN_LIMIT");
    }
}
