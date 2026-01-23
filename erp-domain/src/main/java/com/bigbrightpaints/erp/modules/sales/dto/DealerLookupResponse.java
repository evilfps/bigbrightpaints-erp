package com.bigbrightpaints.erp.modules.sales.dto;

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
        String receivableAccountCode
) {}
