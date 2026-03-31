package com.bigbrightpaints.erp.modules.sales.dto;

import java.util.UUID;

import com.bigbrightpaints.erp.modules.accounting.domain.GstRegistrationType;
import com.bigbrightpaints.erp.modules.sales.domain.DealerPaymentTerms;

public record DealerLookupResponse(
    Long id,
    UUID publicId,
    String name,
    String code,
    Long receivableAccountId,
    String receivableAccountCode,
    String stateCode,
    GstRegistrationType gstRegistrationType,
    DealerPaymentTerms paymentTerms,
    String region,
    String creditStatus) {

  public DealerLookupResponse(
      Long id,
      UUID publicId,
      String name,
      String code,
      Long receivableAccountId,
      String receivableAccountCode) {
    this(
        id,
        publicId,
        name,
        code,
        receivableAccountId,
        receivableAccountCode,
        null,
        GstRegistrationType.UNREGISTERED,
        DealerPaymentTerms.NET_30,
        null,
        "WITHIN_LIMIT");
  }
}
