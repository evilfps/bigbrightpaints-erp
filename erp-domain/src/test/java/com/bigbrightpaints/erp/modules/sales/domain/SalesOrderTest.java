package com.bigbrightpaints.erp.modules.sales.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("critical")
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

    @Test
    void lifecycleCallbacksPopulatePublicIdTimestampsAndFieldAccessors() {
        SalesOrder order = new SalesOrder();
        Company company = new Company();
        company.setTimezone("UTC");
        order.setCompany(company);
        order.setOrderNumber("SO-1");
        order.setStatus("DRAFT");
        order.setTotalAmount(new BigDecimal("125.50"));
        order.setSubtotalAmount(new BigDecimal("100.00"));
        order.setGstTotal(new BigDecimal("25.50"));
        order.setGstTreatment("PER_ITEM");
        order.setGstInclusive(true);
        order.setGstRate(new BigDecimal("18.00"));
        order.setGstRoundingAdjustment(new BigDecimal("0.01"));
        order.setCurrency("USD");
        order.setIdempotencyKey("idem-1");
        order.setIdempotencyHash("hash-1");
        order.setNotes("hello");
        order.setTraceId("trace-1");

        order.prePersist();
        order.preUpdate();

        assertThat(order.getPublicId()).isNotNull();
        assertThat(order.getCreatedAt()).isNotNull();
        assertThat(order.getUpdatedAt()).isNotNull();
        assertThat(order.getOrderNumber()).isEqualTo("SO-1");
        assertThat(order.getStatus()).isEqualTo("DRAFT");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("125.50");
        assertThat(order.getSubtotalAmount()).isEqualByComparingTo("100.00");
        assertThat(order.getGstTotal()).isEqualByComparingTo("25.50");
        assertThat(order.getGstTreatment()).isEqualTo("PER_ITEM");
        assertThat(order.isGstInclusive()).isTrue();
        assertThat(order.getGstRate()).isEqualByComparingTo("18.00");
        assertThat(order.getGstRoundingAdjustment()).isEqualByComparingTo("0.01");
        assertThat(order.getCurrency()).isEqualTo("USD");
        assertThat(order.getIdempotencyKey()).isEqualTo("idem-1");
        assertThat(order.getIdempotencyHash()).isEqualTo("hash-1");
        assertThat(order.getNotes()).isEqualTo("hello");
        assertThat(order.getTraceId()).isEqualTo("trace-1");
        assertThat(order.getItems()).isEmpty();
    }
}
