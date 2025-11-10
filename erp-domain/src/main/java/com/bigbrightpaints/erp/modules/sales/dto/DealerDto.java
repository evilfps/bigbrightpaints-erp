package com.bigbrightpaints.erp.modules.sales.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record DealerDto(Long id,
                        UUID publicId,
                        String name,
                        String code,
                        String email,
                        String phone,
                        String status,
                        BigDecimal creditLimit,
                        BigDecimal outstandingBalance) {}
