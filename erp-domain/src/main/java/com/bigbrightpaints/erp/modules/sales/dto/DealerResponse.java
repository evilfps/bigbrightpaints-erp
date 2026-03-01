package com.bigbrightpaints.erp.modules.sales.dto;

import com.bigbrightpaints.erp.modules.accounting.domain.GstRegistrationType;
import com.bigbrightpaints.erp.modules.sales.domain.DealerPaymentTerms;
import java.math.BigDecimal;
import java.util.UUID;

public record DealerResponse(
        Long id,
        UUID publicId,
        String code,
        String name,
        String companyName,
        String email,
        String phone,
        String address,
        BigDecimal creditLimit,
        BigDecimal outstandingBalance,
        Long receivableAccountId,
        String receivableAccountCode,
        String portalEmail,
        String gstNumber,
        String stateCode,
        GstRegistrationType gstRegistrationType,
        DealerPaymentTerms paymentTerms,
        String region
) {

    public DealerResponse(Long id,
                          UUID publicId,
                          String code,
                          String name,
                          String companyName,
                          String email,
                          String phone,
                          String address,
                          BigDecimal creditLimit,
                          BigDecimal outstandingBalance,
                          Long receivableAccountId,
                          String receivableAccountCode,
                          String portalEmail) {
        this(id, publicId, code, name, companyName, email, phone, address, creditLimit, outstandingBalance,
                receivableAccountId, receivableAccountCode, portalEmail, null, null, GstRegistrationType.UNREGISTERED,
                DealerPaymentTerms.NET_30, null);
    }
}
