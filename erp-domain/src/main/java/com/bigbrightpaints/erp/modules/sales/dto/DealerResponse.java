package com.bigbrightpaints.erp.modules.sales.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record DealerResponse(
        Long id,
        UUID publicId,
        String code,
        String name,
        String email,
        String phone,
        String address,
        BigDecimal creditLimit,
        BigDecimal outstandingBalance,
        Long receivableAccountId,
        String receivableAccountCode,
        String portalEmail,
        String generatedPassword
) {}
