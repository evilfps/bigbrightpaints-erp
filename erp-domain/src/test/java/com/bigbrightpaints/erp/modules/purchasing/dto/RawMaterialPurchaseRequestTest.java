package com.bigbrightpaints.erp.modules.purchasing.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RawMaterialPurchaseRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void constructorAndAccessors_preserveProvidedValues() {
        RawMaterialPurchaseLineRequest line = new RawMaterialPurchaseLineRequest(
                11L,
                "B-101",
                new BigDecimal("25.50"),
                "kg",
                new BigDecimal("120.00"),
                new BigDecimal("18.00"),
                Boolean.TRUE,
                "primary batch"
        );

        RawMaterialPurchaseRequest request = new RawMaterialPurchaseRequest(
                42L,
                "SUP-INV-2026-001",
                LocalDate.of(2026, 2, 13),
                "memo",
                9L,
                77L,
                new BigDecimal("123.45"),
                List.of(line)
        );

        assertThat(request.supplierId()).isEqualTo(42L);
        assertThat(request.invoiceNumber()).isEqualTo("SUP-INV-2026-001");
        assertThat(request.invoiceDate()).isEqualTo(LocalDate.of(2026, 2, 13));
        assertThat(request.memo()).isEqualTo("memo");
        assertThat(request.purchaseOrderId()).isEqualTo(9L);
        assertThat(request.goodsReceiptId()).isEqualTo(77L);
        assertThat(request.taxAmount()).isEqualByComparingTo("123.45");
        assertThat(request.lines()).containsExactly(line);
    }

    @Test
    void validation_reportsTopLevelAndNestedViolations() {
        RawMaterialPurchaseLineRequest invalidLine = new RawMaterialPurchaseLineRequest(
                null,
                null,
                BigDecimal.ZERO,
                null,
                new BigDecimal("-1"),
                null,
                null,
                null
        );
        RawMaterialPurchaseRequest request = new RawMaterialPurchaseRequest(
                null,
                " ",
                null,
                null,
                null,
                null,
                new BigDecimal("-0.01"),
                List.of(invalidLine)
        );

        Set<ConstraintViolation<RawMaterialPurchaseRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains(
                        "supplierId",
                        "invoiceNumber",
                        "invoiceDate",
                        "goodsReceiptId",
                        "taxAmount",
                        "lines[0].rawMaterialId",
                        "lines[0].quantity",
                        "lines[0].costPerUnit"
                );
    }

    @Test
    void validation_rejectsEmptyLines() {
        RawMaterialPurchaseRequest request = new RawMaterialPurchaseRequest(
                42L,
                "SUP-INV-2026-001",
                LocalDate.of(2026, 2, 13),
                null,
                null,
                77L,
                BigDecimal.ZERO,
                List.of()
        );

        Set<ConstraintViolation<RawMaterialPurchaseRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("lines");
    }
}
