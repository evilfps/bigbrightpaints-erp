package com.bigbrightpaints.erp.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LegacyDispatchInvoiceLinkMatcherTest {

    @Test
    void isSlipLinkedToInvoice_requiresExactMatchWhenExplicitInvoiceLinksExist() {
        Invoice invoice = invoice(101L, "ISSUED");
        PackagingSlip matchingSlip = slip(201L, 101L);
        PackagingSlip unrelatedSlip = slip(202L, 999L);

        assertThat(LegacyDispatchInvoiceLinkMatcher.isSlipLinkedToInvoice(
                matchingSlip, invoice, List.of(matchingSlip, unrelatedSlip), 0)).isTrue();
        assertThat(LegacyDispatchInvoiceLinkMatcher.isSlipLinkedToInvoice(
                unrelatedSlip, invoice, List.of(matchingSlip, unrelatedSlip), 0)).isFalse();
    }

    @Test
    void isSlipLinkedToInvoice_failsClosedForAmbiguousLegacyCandidates() {
        Invoice invoice = invoice(102L, "ISSUED");
        PackagingSlip legacySlip = slip(203L, null);
        PackagingSlip otherLegacySlip = slip(204L, null);

        assertThat(LegacyDispatchInvoiceLinkMatcher.isSlipLinkedToInvoice(
                legacySlip, invoice, List.of(legacySlip), 1)).isTrue();
        assertThat(LegacyDispatchInvoiceLinkMatcher.isSlipLinkedToInvoice(
                legacySlip, invoice, List.of(legacySlip), 2)).isFalse();
        assertThat(LegacyDispatchInvoiceLinkMatcher.isSlipLinkedToInvoice(
                legacySlip, invoice, List.of(legacySlip, otherLegacySlip), 1)).isFalse();
    }

    @Test
    void matcherHelpers_coverNullAndExplicitLinkBranches() {
        PackagingSlip explicitSlip = slip(205L, 105L);
        PackagingSlip legacySlip = slip(206L, null);

        assertThat(LegacyDispatchInvoiceLinkMatcher.hasExplicitInvoiceLinks(null)).isFalse();
        assertThat(LegacyDispatchInvoiceLinkMatcher.hasExplicitInvoiceLinks(List.of(legacySlip))).isFalse();
        assertThat(LegacyDispatchInvoiceLinkMatcher.hasExplicitInvoiceLinks(List.of(legacySlip, explicitSlip))).isTrue();
        assertThat(LegacyDispatchInvoiceLinkMatcher.isSlipLinkedToInvoice(null, invoice(105L, "ISSUED"), List.of(legacySlip), 1)).isFalse();
        assertThat(LegacyDispatchInvoiceLinkMatcher.isSlipLinkedToInvoice(legacySlip, null, List.of(legacySlip), 1)).isFalse();
    }

    @Test
    void countCurrentInvoices_countsOnlyCurrentStatuses() {
        List<Invoice> invoices = List.of(
                invoice(111L, "ISSUED"),
                invoice(112L, "PARTIAL"),
                invoice(113L, "PAID"),
                invoice(114L, "DRAFT"),
                invoice(115L, "VOID"),
                invoice(116L, "REVERSED"),
                invoice(117L, null));

        assertThat(LegacyDispatchInvoiceLinkMatcher.countCurrentInvoices(invoices)).isEqualTo(4);
    }

    @Test
    void isCurrentInvoiceStatus_treatsHistoricalStatesAsInactive() {
        assertThat(LegacyDispatchInvoiceLinkMatcher.isCurrentInvoiceStatus(null)).isTrue();
        assertThat(LegacyDispatchInvoiceLinkMatcher.isCurrentInvoiceStatus(" issued ")).isTrue();
        assertThat(LegacyDispatchInvoiceLinkMatcher.isCurrentInvoiceStatus("DRAFT")).isFalse();
        assertThat(LegacyDispatchInvoiceLinkMatcher.isCurrentInvoiceStatus("void")).isFalse();
        assertThat(LegacyDispatchInvoiceLinkMatcher.isCurrentInvoiceStatus("REVERSED")).isFalse();
    }

    private Invoice invoice(Long id, String status) {
        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", id);
        invoice.setStatus(status);
        SalesOrder salesOrder = new SalesOrder();
        ReflectionTestUtils.setField(salesOrder, "id", 900L + id);
        invoice.setSalesOrder(salesOrder);
        return invoice;
    }

    private PackagingSlip slip(Long id, Long invoiceId) {
        PackagingSlip slip = new PackagingSlip();
        ReflectionTestUtils.setField(slip, "id", id);
        slip.setInvoiceId(invoiceId);
        return slip;
    }
}
