package com.bigbrightpaints.erp.modules.accounting.dto;

import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountDto(Long id,
                         UUID publicId,
                         String code,
                         String name,
                         AccountType type,
                         BigDecimal balance) {}
