package com.bigbrightpaints.erp.modules.invoice.dto;

import java.math.BigDecimal;

public record InvoiceLineDto(Long id,
                             String productCode,
                             String description,
                             BigDecimal quantity,
                             BigDecimal unitPrice,
                             BigDecimal taxRate,
                             BigDecimal lineTotal) {}
