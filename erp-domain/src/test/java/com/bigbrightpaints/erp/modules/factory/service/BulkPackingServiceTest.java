package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackRequest;
import com.bigbrightpaints.erp.modules.factory.dto.PackingLineRequest;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class BulkPackingServiceTest {

    @Mock
    private CompanyEntityLookup companyEntityLookup;
    @Mock
    private com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository finishedGoodRepository;
    @Mock
    private FinishedGoodBatchRegistrar finishedGoodBatchRegistrar;
    @Mock
    private PackingJournalBuilder packingJournalBuilder;

    @Test
    void parseSizeInLitersSupportsMlAndLtr() {
        assertThat(BulkPackingOrchestrator.parseSizeInLiters("500ML"))
                .isEqualByComparingTo(new BigDecimal("0.500000"));
        assertThat(BulkPackingOrchestrator.parseSizeInLiters("1LTR"))
                .isEqualByComparingTo(new BigDecimal("1"));
        assertThat(BulkPackingOrchestrator.parseSizeInLiters("0.5L"))
                .isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    void parseSizeInLitersReturnsNullForInvalid() {
        assertThat(BulkPackingOrchestrator.parseSizeInLiters("SIZE"))
                .isNull();
    }

    @Test
    void buildPackReference_distinguishesPackagingConsumptionMode() {
        BulkPackingService service = new BulkPackingService(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        FinishedGoodBatch bulkBatch = new FinishedGoodBatch();
        ReflectionTestUtils.setField(bulkBatch, "id", 42L);
        bulkBatch.setBatchCode("bulk-42");

        BulkPackRequest consumePackaging = new BulkPackRequest(
                42L,
                List.of(new BulkPackRequest.PackLine(7L, new BigDecimal("10"), "1L", "L")),
                LocalDate.of(2026, 3, 23),
                null,
                null,
                null,
                false);
        BulkPackRequest skipPackaging = new BulkPackRequest(
                42L,
                List.of(new BulkPackRequest.PackLine(7L, new BigDecimal("10"), "1L", "L")),
                LocalDate.of(2026, 3, 23),
                null,
                null,
                null,
                true);

        String consumeReference = ReflectionTestUtils.invokeMethod(service, "buildPackReference", bulkBatch, consumePackaging);
        String skipReference = ReflectionTestUtils.invokeMethod(service, "buildPackReference", bulkBatch, skipPackaging);

        assertThat(consumeReference).isNotEqualTo(skipReference);
    }

    @Test
    void createChildBatch_requiresActiveFinishedGood() {
        BulkPackingOrchestrator orchestrator = new BulkPackingOrchestrator(
                companyEntityLookup,
                finishedGoodRepository,
                finishedGoodBatchRegistrar,
                packingJournalBuilder);
        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", 9L);

        FinishedGood childFinishedGood = new FinishedGood();
        ReflectionTestUtils.setField(childFinishedGood, "id", 77L);
        childFinishedGood.setCompany(company);
        childFinishedGood.setProductCode("FG-CHILD-1L");
        childFinishedGood.setName("Primer 1L");

        FinishedGoodBatch parentBatch = new FinishedGoodBatch();
        parentBatch.setFinishedGood(childFinishedGood);
        parentBatch.setManufacturedAt(Instant.parse("2026-03-20T10:15:30Z"));

        FinishedGoodBatch childBatch = new FinishedGoodBatch();
        childBatch.setFinishedGood(childFinishedGood);
        when(companyEntityLookup.lockActiveFinishedGood(company, 77L)).thenReturn(childFinishedGood);
        when(finishedGoodBatchRegistrar.registerReceipt(any()))
                .thenReturn(new FinishedGoodBatchRegistrar.ReceiptRegistrationResult(childBatch, null));

        FinishedGoodBatch result = orchestrator.createChildBatch(
                company,
                parentBatch,
                new BulkPackRequest.PackLine(77L, new BigDecimal("6"), "1L", "L"),
                new BigDecimal("80.00"),
                new BigDecimal("2.50"),
                LocalDate.of(2026, 3, 23),
                "PACK-42-REF");

        assertThat(result).isSameAs(childBatch);
        verify(companyEntityLookup).lockActiveFinishedGood(company, 77L);
    }

    @Test
    void resolveTargetFinishedGood_requiresActiveChildSku() {
        PackingProductSupport support = new PackingProductSupport(companyEntityLookup, finishedGoodRepository);
        Company company = new Company();

        FinishedGood activeChild = new FinishedGood();
        activeChild.setProductCode("FG-PARENT-1L");
        when(companyEntityLookup.lockActiveFinishedGood(company, 88L)).thenReturn(activeChild);

        ProductionLog log = new ProductionLog();
        ProductionProduct product = new ProductionProduct();
        product.setSkuCode("FG-PARENT");
        log.setProduct(product);

        FinishedGood resolved = support.resolveTargetFinishedGood(
                company,
                log,
                new PackingLineRequest(88L, 1, "1L", new BigDecimal("1.0"), 1, 1, 1),
                null);

        assertThat(resolved).isSameAs(activeChild);
        verify(companyEntityLookup).lockActiveFinishedGood(company, 88L);
    }
}
