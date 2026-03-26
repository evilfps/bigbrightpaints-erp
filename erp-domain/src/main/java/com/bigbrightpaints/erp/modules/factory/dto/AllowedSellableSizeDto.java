package com.bigbrightpaints.erp.modules.factory.dto;

import java.math.BigDecimal;

public record AllowedSellableSizeDto(
    Long childFinishedGoodId,
    String childSkuCode,
    String childFinishedGoodName,
    Long sizeVariantId,
    String sizeLabel,
    Integer piecesPerBox,
    BigDecimal litersPerUnit,
    String productFamilyName) {}
