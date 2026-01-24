package com.bigbrightpaints.erp.modules.sales.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import org.junit.jupiter.api.Test;

class SalesOrderReferenceTest {

    @Test
    void normalizeOrderNumber_null_returnsUnknown() {
        assertThat(SalesOrderReference.normalizeOrderNumber((String) null)).isEqualTo("UNKNOWN");
    }

    @Test
    void normalizeOrderNumber_blank_returnsUnknown() {
        assertThat(SalesOrderReference.normalizeOrderNumber("   ")).isEqualTo("UNKNOWN");
    }

    @Test
    void normalizeOrderNumber_stripsNonAlnum() {
        assertThat(SalesOrderReference.normalizeOrderNumber(" ab-12_x ")).isEqualTo("AB-12X");
    }

    @Test
    void normalizeOrderNumber_uppercases() {
        assertThat(SalesOrderReference.normalizeOrderNumber("inv-xyz")).isEqualTo("INV-XYZ");
    }

    @Test
    void invoiceReference_prefixAndNormalize() {
        assertThat(SalesOrderReference.invoiceReference(" so-1 ")).isEqualTo("INV-SO-1");
    }

    @Test
    void cogsReference_prefixAndNormalize() {
        assertThat(SalesOrderReference.cogsReference(" so-2 ")).isEqualTo("COGS-SO-2");
    }

    @Test
    void legacySalesJournalPrefix_prefixAndNormalize() {
        assertThat(SalesOrderReference.legacySalesJournalPrefix("so-3")).isEqualTo("SALE-SO-3");
    }

    @Test
    void normalizeOrderNumber_orderObjectUsesOrderNumber() {
        SalesOrder order = new SalesOrder();
        order.setOrderNumber("so-9");
        assertThat(SalesOrderReference.normalizeOrderNumber(order)).isEqualTo("SO-9");
    }

    @Test
    void normalizeOrderNumber_orderObjectUsesIdWhenMissingNumber() {
        SalesOrder order = new SalesOrder();
        order.setOrderNumber(null);
        assertThat(SalesOrderReference.normalizeOrderNumber(order)).isEqualTo("UNKNOWN");
    }
}
