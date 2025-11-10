package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountDto(Long id,
                         UUID publicId,
                         String code,
                         String name,
                         String type,
                         BigDecimal balance) {}
