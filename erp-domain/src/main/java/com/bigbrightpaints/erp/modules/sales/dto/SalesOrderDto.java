package com.bigbrightpaints.erp.modules.sales.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import java.util.List;

public record SalesOrderDto(Long id,
                            UUID publicId,
                            String orderNumber,
                            String status,
                            BigDecimal totalAmount,
                            BigDecimal subtotalAmount,
                            BigDecimal gstTotal,
                            BigDecimal gstRate,
                            String gstTreatment,
                            boolean gstInclusive,
                            BigDecimal gstRoundingAdjustment,
                            String currency,
                            String dealerName,
                            String paymentMode,
                            String traceId,
                            Instant createdAt,
                            List<SalesOrderItemDto> items,
                            List<SalesOrderStatusHistoryDto> timeline) {}
