package com.bigbrightpaints.erp.modules.purchasing.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.bigbrightpaints.erp.modules.accounting.domain.GstRegistrationType;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierPaymentTerms;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierStatus;

public record SupplierResponse(
    Long id,
    UUID publicId,
    String code,
    String name,
    SupplierStatus status,
    String email,
    String phone,
    String address,
    BigDecimal creditLimit,
    BigDecimal balance,
    Long payableAccountId,
    String payableAccountCode,
    String gstNumber,
    String stateCode,
    GstRegistrationType gstRegistrationType,
    SupplierPaymentTerms paymentTerms,
    String bankAccountName,
    String bankAccountNumber,
    String bankIfsc,
    String bankBranch) {

  public SupplierResponse(
      Long id,
      UUID publicId,
      String code,
      String name,
      SupplierStatus status,
      String email,
      String phone,
      String address,
      BigDecimal creditLimit,
      BigDecimal balance,
      Long payableAccountId,
      String payableAccountCode) {
    this(
        id,
        publicId,
        code,
        name,
        status,
        email,
        phone,
        address,
        creditLimit,
        balance,
        payableAccountId,
        payableAccountCode,
        null,
        null,
        GstRegistrationType.UNREGISTERED,
        SupplierPaymentTerms.NET_30,
        null,
        null,
        null,
        null);
  }
}
