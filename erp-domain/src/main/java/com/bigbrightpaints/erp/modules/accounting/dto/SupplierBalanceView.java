package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;

public record SupplierBalanceView(Long supplierId, BigDecimal balance) {}
