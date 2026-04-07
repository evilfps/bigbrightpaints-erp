package com.bigbrightpaints.erp.modules.sales.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.bigbrightpaints.erp.modules.accounting.domain.GstRegistrationType;
import com.bigbrightpaints.erp.modules.sales.domain.DealerPaymentTerms;

public record DealerLookupResponse(
    Long id,
    UUID publicId,
    String name,
    String code,
    Long arAccountId,
    Long receivableAccountId,
    String receivableAccountCode,
    String stateCode,
    GstRegistrationType gstRegistrationType,
    DealerPaymentTerms paymentTerms,
    String region,
    BigDecimal creditLimit,
    BigDecimal outstandingBalance,
    String creditStatus) {

  public DealerLookupResponse(
      Long id,
      UUID publicId,
      String name,
      String code,
      Long arAccountId,
      Long receivableAccountId,
      String receivableAccountCode) {
    this(
        id,
        publicId,
        name,
        code,
        arAccountId,
        receivableAccountId,
        receivableAccountCode,
        null,
        GstRegistrationType.UNREGISTERED,
        DealerPaymentTerms.NET_30,
        null,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        "WITHIN_LIMIT");
  }
}
