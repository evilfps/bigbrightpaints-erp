package com.bigbrightpaints.erp.modules.inventory.domain;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RawMaterialStockConstraintTest {

    @Test
    void setCurrentStock_rejectsNegativeValues() {
        RawMaterial material = new RawMaterial();
        material.setSku("RM-NEG");
        material.setName("Negative Guard");

        assertThatThrownBy(() -> material.setCurrentStock(new BigDecimal("-0.01")))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION);
                    assertThat(ex.getMessage()).isEqualTo("Raw material stock cannot be negative");
                    assertThat(ex.getDetails()).containsEntry("rawMaterialSku", "RM-NEG");
                });
    }

    @Test
    void setCurrentStock_allowsNullAsZero() {
        RawMaterial material = new RawMaterial();

        material.setCurrentStock(null);

        assertThat(material.getCurrentStock()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
