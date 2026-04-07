package com.bigbrightpaints.erp.modules.sales.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SalesOrderDto(
    Long id,
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
    String paymentTerms,
    String traceId,
    Instant createdAt,
    List<SalesOrderItemDto> items,
    List<SalesOrderStatusHistoryDto> timeline) {

  public SalesOrderDto(
      Long id,
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
      List<SalesOrderStatusHistoryDto> timeline) {
    this(
        id,
        publicId,
        orderNumber,
        status,
        totalAmount,
        subtotalAmount,
        gstTotal,
        gstRate,
        gstTreatment,
        gstInclusive,
        gstRoundingAdjustment,
        currency,
        dealerName,
        paymentMode,
        null,
        traceId,
        createdAt,
        items,
        timeline);
  }
}
