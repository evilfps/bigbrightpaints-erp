package com.bigbrightpaints.erp.modules.purchasing.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record SupplierResponse(Long id,
                               UUID publicId,
                               String code,
                               String name,
                               String status,
                               String email,
                               String phone,
                               String address,
                               BigDecimal creditLimit,
                               BigDecimal outstandingBalance,
                               Long payableAccountId,
                               String payableAccountCode) {
}
