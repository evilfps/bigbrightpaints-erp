package com.bigbrightpaints.erp.modules.production.dto;

import java.math.BigDecimal;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;

public record CatalogItemCreateCommand(
    Long brandId,
    String brandName,
    String brandCode,
    @NotBlank(message = "Product name is required") String productName,
    @NotBlank(message = "Category is required") String category,
    @NotBlank(message = "Item class is required") String itemClass,
    String defaultColour,
    String sizeLabel,
    String unitOfMeasure,
    String hsnCode,
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String customSkuCode,
    BigDecimal basePrice,
    BigDecimal gstRate,
    BigDecimal minDiscountPercent,
    BigDecimal minSellingPrice,
    Map<String, Object> metadata,
    Boolean active) {
  public CatalogItemCreateCommand(
      Long brandId,
      String brandName,
      String brandCode,
      String productName,
      String category,
      String itemClass,
      String defaultColour,
      String sizeLabel,
      String unitOfMeasure,
      String hsnCode,
      String customSkuCode,
      BigDecimal basePrice,
      BigDecimal gstRate,
      BigDecimal minDiscountPercent,
      BigDecimal minSellingPrice,
      Map<String, Object> metadata) {
    this(
        brandId,
        brandName,
        brandCode,
        productName,
        category,
        itemClass,
        defaultColour,
        sizeLabel,
        unitOfMeasure,
        hsnCode,
        customSkuCode,
        basePrice,
        gstRate,
        minDiscountPercent,
        minSellingPrice,
        metadata,
        null);
  }
}
