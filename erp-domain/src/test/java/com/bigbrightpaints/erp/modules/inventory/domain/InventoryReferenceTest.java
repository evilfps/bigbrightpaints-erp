package com.bigbrightpaints.erp.modules.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InventoryReferenceTest {

    @Test
    void constants_areUppercaseAndNonBlank() {
        List<String> values = List.of(
                InventoryReference.PRODUCTION_LOG,
                InventoryReference.RAW_MATERIAL_PURCHASE,
                InventoryReference.OPENING_STOCK,
                InventoryReference.SALES_ORDER,
                InventoryReference.MANUFACTURING_ORDER,
                InventoryReference.PURCHASE_RETURN,
                InventoryReference.PACKING_RECORD
        );
        values.forEach(value -> {
            assertThat(value).isNotBlank();
            assertThat(value).isEqualTo(value.toUpperCase());
        });
    }

    @Test
    void constants_areUnique() {
        Set<String> values = Set.of(
                InventoryReference.PRODUCTION_LOG,
                InventoryReference.RAW_MATERIAL_PURCHASE,
                InventoryReference.OPENING_STOCK,
                InventoryReference.SALES_ORDER,
                InventoryReference.MANUFACTURING_ORDER,
                InventoryReference.PURCHASE_RETURN,
                InventoryReference.PACKING_RECORD
        );
        assertThat(values).hasSize(7);
    }
}
