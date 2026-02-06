package com.bigbrightpaints.erp.truthsuite.p2p;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("concurrency")
@Tag("reconciliation")
class TS_P2PGoodsReceiptIdempotencyTest {

    private static final String PURCHASING_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchasingService.java";

    @Test
    void goodsReceiptRequiresIdempotencyAndOpenPeriod() {
        TruthSuiteFileAssert.assertContains(
                PURCHASING_SERVICE,
                "String idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey());",
                "\"Idempotency key is required for goods receipts\"",
                "goodsReceiptRepository.findWithLinesByCompanyAndIdempotencyKey(company, idempotencyKey)",
                "accountingPeriodService.requireOpenPeriod(company, receiptDate);",
                "assertIdempotencyMatch(existing, requestSignature, idempotencyKey);");
    }

    @Test
    void goodsReceiptRetryPathIsDuplicateSafe() {
        TruthSuiteFileAssert.assertContains(
                PURCHASING_SERVICE,
                "if (!isDataIntegrityViolation(ex)) {",
                "GoodsReceipt concurrent = goodsReceiptRepository.findWithLinesByCompanyAndIdempotencyKey(company, idempotencyKey)",
                "assertIdempotencyMatch(concurrent, requestSignature, idempotencyKey);");
    }

    @Test
    void goodsReceiptPersistsInventoryMovementsThroughRawMaterialService() {
        TruthSuiteFileAssert.assertContains(
                PURCHASING_SERVICE,
                "RawMaterialService.ReceiptResult receiptResult = rawMaterialService.recordReceipt(rawMaterial.getId(), batchRequest, context);",
                "GoodsReceipt savedReceipt = goodsReceiptRepository.saveAndFlush(receipt);");
    }
}
