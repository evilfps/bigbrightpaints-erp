package com.bigbrightpaints.erp.modules.sales.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SalesOrderTest {

    @Test
    void paymentMode_defaultsToCreditAndCanBeOverridden() {
        SalesOrder order = new SalesOrder();

        assertThat(order.getPaymentMode()).isEqualTo("CREDIT");

        order.setPaymentMode("CASH");

        assertThat(order.getPaymentMode()).isEqualTo("CASH");
    }

    @Test
    void idempotencyMarkerHelpersReflectMarkerPresence() {
        SalesOrder order = new SalesOrder();

        assertThat(order.hasSalesJournalPosted()).isFalse();
        assertThat(order.hasCogsJournalPosted()).isFalse();
        assertThat(order.hasInvoiceIssued()).isFalse();

        order.setSalesJournalEntryId(10L);
        order.setCogsJournalEntryId(11L);
        order.setFulfillmentInvoiceId(12L);

        assertThat(order.hasSalesJournalPosted()).isTrue();
        assertThat(order.hasCogsJournalPosted()).isTrue();
        assertThat(order.hasInvoiceIssued()).isTrue();
    }
}
