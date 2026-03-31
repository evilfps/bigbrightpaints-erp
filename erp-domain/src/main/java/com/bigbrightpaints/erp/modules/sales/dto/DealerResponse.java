package com.bigbrightpaints.erp.modules.sales.dto;

import java.util.UUID;

import com.bigbrightpaints.erp.modules.accounting.domain.GstRegistrationType;
import com.bigbrightpaints.erp.modules.sales.domain.DealerPaymentTerms;

public record DealerResponse(
    Long id,
    UUID publicId,
    String code,
    String name,
    String companyName,
    String email,
    String phone,
    String address,
    Long receivableAccountId,
    String receivableAccountCode,
    String portalEmail,
    String gstNumber,
    String stateCode,
    GstRegistrationType gstRegistrationType,
    DealerPaymentTerms paymentTerms,
    String region,
    String creditStatus) {

  public DealerResponse(
      Long id,
      UUID publicId,
      String code,
      String name,
      String companyName,
      String email,
      String phone,
      String address,
      Long receivableAccountId,
      String receivableAccountCode,
      String portalEmail) {
    this(
        id,
        publicId,
        code,
        name,
        companyName,
        email,
        phone,
        address,
        receivableAccountId,
        receivableAccountCode,
        portalEmail,
        null,
        null,
        GstRegistrationType.UNREGISTERED,
        DealerPaymentTerms.NET_30,
        null,
        "WITHIN_LIMIT");
  }
}
