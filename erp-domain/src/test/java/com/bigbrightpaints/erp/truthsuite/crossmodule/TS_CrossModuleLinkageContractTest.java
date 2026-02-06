package com.bigbrightpaints.erp.truthsuite.crossmodule;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("reconciliation")
class TS_CrossModuleLinkageContractTest {

    private static final String SALES_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java";
    private static final String PURCHASING_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchasingService.java";
    private static final String PAYROLL_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/hr/service/PayrollService.java";
    private static final String SCAN_SQL = "../scripts/db_predeploy_scans.sql";

    @Test
    void o2cChainPersistsSlipInvoiceJournalAndOrderLinks() {
        TruthSuiteFileAssert.assertContains(
                SALES_SERVICE,
                "slip.setJournalEntryId(arJournalEntryId);",
                "slip.setCogsJournalEntryId(cogsJournalId);",
                "slip.setInvoiceId(invoice.getId());",
                "order.setSalesJournalEntryId(arJournalEntryId);",
                "order.setCogsJournalEntryId(cogsJournalId);",
                "order.setFulfillmentInvoiceId(invoice.getId());");
    }

    @Test
    void p2pChainPersistsGrnPurchaseInvoiceAndJournalLinks() {
        TruthSuiteFileAssert.assertContains(
                PURCHASING_SERVICE,
                "purchase.setJournalEntry(linkedJournal);",
                "purchase.setGoodsReceipt(goodsReceipt);",
                "movement.setJournalEntryId(entryId);",
                "goodsReceipt.setStatus(\"INVOICED\");");
    }

    @Test
    void payrollChainPersistsRunPostPaymentLinkage() {
        TruthSuiteFileAssert.assertContains(
                PAYROLL_SERVICE,
                "run.setJournalEntryId(journal.id());",
                "run.setStatus(PayrollRun.PayrollStatus.POSTED);",
                "if (run.getPaymentJournalEntryId() == null) {",
                "run.setStatus(PayrollRun.PayrollStatus.PAID);");
    }

    @Test
    void predeployScansBlockBrokenCrossModuleLinkage() {
        TruthSuiteFileAssert.assertContains(
                SCAN_SQL,
                "-- 2) Dispatched packaging slips missing required links (invoice + AR journal + COGS journal)",
                "-- 3) Invoices missing journal entry",
                "-- 3b) Posted-ish purchases missing journal entry (status alone is not proof)",
                "-- 4) Packaging slips pointing to missing invoices (or cross-company mismatch)");
    }
}
