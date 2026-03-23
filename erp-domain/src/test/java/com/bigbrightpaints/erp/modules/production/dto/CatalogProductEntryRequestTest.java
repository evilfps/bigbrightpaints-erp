package com.bigbrightpaints.erp.modules.production.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("critical")
class CatalogProductEntryRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void setMetadata_copiesValues_andFallsBackToEmptyMap() {
        CatalogProductEntryRequest request = new CatalogProductEntryRequest();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("productType", "decorative");

        request.setMetadata(metadata);
        metadata.put("productType", "industrial");

        assertThat(request.getMetadata()).containsEntry("productType", "decorative");

        request.setMetadata(null);

        assertThat(request.getMetadata()).isEmpty();
    }

    @Test
    void beanValidation_rejectsOutOfRangePricingAndOversizedPersistedFields() {
        CatalogProductEntryRequest request = new CatalogProductEntryRequest();
        request.setBrandId(11L);
        request.setBaseProductName("Primer");
        request.setCategory("RAW_MATERIAL");
        request.setUnitOfMeasure("L".repeat(65));
        request.setHsnCode("9".repeat(33));
        request.setGstRate(new BigDecimal("18.00"));
        request.setColors(java.util.List.of("WHITE"));
        request.setSizes(java.util.List.of("1L"));
        request.setBasePrice(new BigDecimal("-1.00"));
        request.setMinDiscountPercent(new BigDecimal("101.00"));
        request.setMinSellingPrice(new BigDecimal("-2.00"));

        Map<String, String> violations = validator.validate(request).stream()
                .collect(java.util.stream.Collectors.toMap(
                        violation -> violation.getPropertyPath().toString(),
                        violation -> violation.getMessage(),
                        (left, right) -> left,
                        LinkedHashMap::new));

        assertThat(violations)
                .containsEntry("unitOfMeasure", "unitOfMeasure cannot exceed 64 characters")
                .containsEntry("hsnCode", "hsnCode cannot exceed 32 characters")
                .containsEntry("basePrice", "basePrice cannot be negative")
                .containsEntry("minDiscountPercent", "minDiscountPercent cannot be greater than 100")
                .containsEntry("minSellingPrice", "minSellingPrice cannot be negative")
                .doesNotContainKey("itemClass")
                .doesNotContainKey("category");
    }
}
