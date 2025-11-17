package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;

public record AccountBalanceView(Long accountId, BigDecimal balance) {}
