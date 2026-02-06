package com.bigbrightpaints.erp.truthsuite.o2c;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("reconciliation")
class TS_O2CDispatchCanonicalPostingTest {

    private static final String SALES_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java";

    @Test
    void confirmDispatchUsesLockingAndCanonicalFacadePosting() {
        TruthSuiteFileAssert.assertContains(
                SALES_SERVICE,
                "public DispatchConfirmResponse confirmDispatch(DispatchConfirmRequest request)",
                "packagingSlipRepository.findAndLockByIdAndCompany(request.packingSlipId(), company)",
                "finishedGoodsService.confirmDispatch(",
                "accountingFacade.postCogsJournal(",
                "accountingFacade.postSalesJournal(",
                "invoice = invoiceRepository.save(invoice);",
                "packagingSlipRepository.save(slip);",
                "salesOrderRepository.save(order);");
    }

    @Test
    void cogsPostingPrecedesArPostingAndPersistsLinkage() {
        TruthSuiteFileAssert.assertContainsInOrder(
                SALES_SERVICE,
                "finishedGoodsService.confirmDispatch(",
                "accountingFacade.postCogsJournal(",
                "finishedGoodsService.linkDispatchMovementsToJournal(slip.getId(), cogsJournalId);",
                "accountingFacade.postSalesJournal(",
                "invoice = invoiceRepository.save(invoice);");
    }

    @Test
    void dispatchFlowAvoidsDirectAccountingServicePosting() {
        String source = TruthSuiteFileAssert.read(SALES_SERVICE);
        assertFalse(
                source.contains("accountingService."),
                "Sales dispatch flow must route posting through AccountingFacade only");
    }
}
