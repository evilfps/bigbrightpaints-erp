package com.bigbrightpaints.erp.modules.sales.dto;

import java.math.BigDecimal;
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
    Long arAccountId,
    Long receivableAccountId,
    String receivableAccountCode,
    String portalEmail,
    String gstNumber,
    String stateCode,
    GstRegistrationType gstRegistrationType,
    DealerPaymentTerms paymentTerms,
    String region,
    BigDecimal creditLimit,
    BigDecimal outstandingBalance,
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
      Long arAccountId,
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
        arAccountId,
        receivableAccountId,
        receivableAccountCode,
        portalEmail,
        null,
        null,
        GstRegistrationType.UNREGISTERED,
        DealerPaymentTerms.NET_30,
        null,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        "WITHIN_LIMIT");
  }
}
