package com.bigbrightpaints.erp.modules.production.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CatalogProductEntryRequest {

    @NotNull(message = "brandId is required")
    private Long brandId;

    @NotBlank(message = "baseProductName is required")
    private String baseProductName;

    @NotBlank(message = "category is required")
    private String category;

    @NotBlank(message = "unitOfMeasure is required")
    @Size(max = 64, message = "unitOfMeasure cannot exceed 64 characters")
    private String unitOfMeasure;

    @NotBlank(message = "hsnCode is required")
    @Size(max = 32, message = "hsnCode cannot exceed 32 characters")
    private String hsnCode;

    @NotNull(message = "gstRate is required")
    @DecimalMin(value = "0.00", message = "gstRate cannot be negative")
    @DecimalMax(value = "100.00", message = "gstRate cannot be greater than 100")
    private BigDecimal gstRate;

    @NotEmpty(message = "colors must contain at least one value")
    private List<String> colors;

    @NotEmpty(message = "sizes must contain at least one value")
    private List<String> sizes;

    @DecimalMin(value = "0.00", message = "basePrice cannot be negative")
    private BigDecimal basePrice;

    @DecimalMin(value = "0.00", message = "minDiscountPercent cannot be negative")
    @DecimalMax(value = "100.00", message = "minDiscountPercent cannot be greater than 100")
    private BigDecimal minDiscountPercent;

    @DecimalMin(value = "0.00", message = "minSellingPrice cannot be negative")
    private BigDecimal minSellingPrice;
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @JsonIgnore
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    public Long getBrandId() {
        return brandId;
    }

    public void setBrandId(Long brandId) {
        this.brandId = brandId;
    }

    public String getBaseProductName() {
        return baseProductName;
    }

    public void setBaseProductName(String baseProductName) {
        this.baseProductName = baseProductName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getUnitOfMeasure() {
        return unitOfMeasure;
    }

    public void setUnitOfMeasure(String unitOfMeasure) {
        this.unitOfMeasure = unitOfMeasure;
    }

    public String getHsnCode() {
        return hsnCode;
    }

    public void setHsnCode(String hsnCode) {
        this.hsnCode = hsnCode;
    }

    public BigDecimal getGstRate() {
        return gstRate;
    }

    public void setGstRate(BigDecimal gstRate) {
        this.gstRate = gstRate;
    }

    public List<@NotBlank(message = "colors must not contain blank values") String> getColors() { return colors; }

    public void setColors(List<String> colors) {
        this.colors = colors;
    }

    public List<@NotBlank(message = "sizes must not contain blank values") String> getSizes() { return sizes; }

    public void setSizes(List<String> sizes) {
        this.sizes = sizes;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public void setBasePrice(BigDecimal basePrice) {
        this.basePrice = basePrice;
    }

    public BigDecimal getMinDiscountPercent() {
        return minDiscountPercent;
    }

    public void setMinDiscountPercent(BigDecimal minDiscountPercent) {
        this.minDiscountPercent = minDiscountPercent;
    }

    public BigDecimal getMinSellingPrice() {
        return minSellingPrice;
    }

    public void setMinSellingPrice(BigDecimal minSellingPrice) {
        this.minSellingPrice = minSellingPrice;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }

    @JsonAnySetter
    public void captureUnknownField(String fieldName, Object value) {
        unknownFields.put(fieldName, value);
    }

    @JsonIgnore
    public Map<String, Object> getUnknownFields() {
        return Collections.unmodifiableMap(unknownFields);
    }
}
