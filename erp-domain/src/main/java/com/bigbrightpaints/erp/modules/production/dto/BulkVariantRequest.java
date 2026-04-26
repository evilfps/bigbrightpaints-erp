package com.bigbrightpaints.erp.modules.production.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Request to generate multiple product variants (color x size) in one shot.
 */
public record BulkVariantRequest(
    Long brandId,
    String brandName,
    String brandCode,
    @NotBlank String baseProductName,
    @NotBlank String category,
    String hsnCode,
    List<String> colors,
    List<String> sizes,
    List<@Valid ColorSizeMatrixEntry> colorSizeMatrix,
    String unitOfMeasure,
    String skuPrefix,
    BigDecimal basePrice,
    BigDecimal gstRate,
    BigDecimal minDiscountPercent,
    BigDecimal minSellingPrice,
    PackagingMetadata packagingDefaults,
    List<@Valid VariantPackagingOverride> packagingOverrides,
    Map<String, Object> metadata) {
  public record ColorSizeMatrixEntry(@NotBlank String color, List<String> sizes) {}

  public record PackagingMetadata(
      Integer piecesPerCarton, Integer piecesPerBox, BigDecimal defaultPackQuantity) {}

  public record VariantPackagingOverride(
      @NotBlank String color, @NotBlank String size, @Valid PackagingMetadata packaging) {}
}
