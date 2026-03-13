package com.bigbrightpaints.erp.core.util;

import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class LegacyDispatchInvoiceLinkMatcher {

    private LegacyDispatchInvoiceLinkMatcher() {
    }

    public static boolean isSlipLinkedToInvoice(PackagingSlip slip,
                                                Invoice invoice,
                                                List<PackagingSlip> candidateSlips,
                                                int salesOrderInvoiceCount) {
        if (slip == null || invoice == null) {
            return false;
        }
        boolean hasExplicitInvoiceLinks = candidateSlips != null
                && candidateSlips.stream().anyMatch(candidate -> candidate != null && candidate.getInvoiceId() != null);
        if (hasExplicitInvoiceLinks) {
            return slip.getInvoiceId() != null
                    && invoice.getId() != null
                    && slip.getInvoiceId().equals(invoice.getId());
        }
        long candidateCount = candidateSlips == null
                ? 0
                : candidateSlips.stream().filter(Objects::nonNull).count();
        return candidateCount == 1 && salesOrderInvoiceCount == 1;
    }

    public static boolean hasExplicitInvoiceLinks(List<PackagingSlip> candidateSlips) {
        return candidateSlips != null
                && candidateSlips.stream().anyMatch(candidate -> candidate != null && candidate.getInvoiceId() != null);
    }

    public static int countCurrentInvoices(List<Invoice> invoices) {
        if (invoices == null) {
            return 0;
        }
        return (int) invoices.stream()
                .filter(Objects::nonNull)
                .filter(invoice -> isCurrentInvoiceStatus(invoice.getStatus()))
                .count();
    }

    public static boolean isCurrentInvoiceStatus(String status) {
        if (status == null) {
            return true;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return !normalized.equals("DRAFT")
                && !normalized.equals("VOID")
                && !normalized.equals("REVERSED");
    }
}
