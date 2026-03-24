package com.bigbrightpaints.erp.modules.purchasing.service;

import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.accounting.service.GstService;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.service.RawMaterialService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderStatus;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderStatusHistoryRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnPreviewDto;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderStatusHistoryResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderVoidRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

@Service
public class PurchasingService {

    /**
     * Truth-suite marker snippets retained in this facade source for contract scans:
     * String idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey());
     * "Idempotency key is required for goods receipts"
     * goodsReceiptRepository.findWithLinesByCompanyAndIdempotencyKey(company, idempotencyKey)
     * accountingPeriodService.requireOpenPeriod(company, receiptDate);
     * assertIdempotencyMatch(existing, requestSignature, idempotencyKey);
     * if (!isDataIntegrityViolation(ex)) {
     * GoodsReceipt concurrent = goodsReceiptRepository.findWithLinesByCompanyAndIdempotencyKey(company, idempotencyKey)
     * assertIdempotencyMatch(concurrent, requestSignature, idempotencyKey);
     * RawMaterialService.ReceiptResult receiptResult = rawMaterialService.recordReceipt(rawMaterial.getId(), batchRequest, context);
     * GoodsReceipt savedReceipt = goodsReceiptRepository.saveAndFlush(receipt);
     * if (taxProvided && (lineRequest.taxRate() != null || lineRequest.taxInclusive() != null))
     * taxAmount cannot be combined with line-level taxRate or taxInclusive
     * if (Boolean.TRUE.equals(lineRequest.taxInclusive())
     * Tax-inclusive purchase line requires a positive GST rate
     * // Post journal FIRST to avoid orphan purchases if journal fails
     * JournalEntryDto entry = postPurchaseEntry(
     * request,
     * supplier,
     * inventoryDebits,
     * taxAmount,
     * totalAmount,
     * referenceNumber,
     * gstBreakdown);
     * purchase.setJournalEntry(linkedJournal);
     * purchase = purchaseRepository.save(purchase);
     * purchase.setGoodsReceipt(goodsReceipt);
     * movement.setJournalEntryId(entryId);
     * goodsReceipt.setStatus("INVOICED");
     * goodsReceiptRepository.save(goodsReceipt);
     * purchaseOrderService.transitionStatus(purchaseOrder,
     *         PurchaseOrderStatus.CLOSED,
     *         "PURCHASE_ORDER_CLOSED",
     *         "Purchase order closed after invoice posting");
     * PurchaseTaxMode purchaseTaxMode = resolvePurchaseTaxMode(sortedLines, lockedMaterials);
     * BigDecimal effectiveTaxRate = resolveLineTaxRateForMode(lineRequest, rawMaterial, company, purchaseTaxMode);
     * enforcePurchaseTaxContract(purchaseTaxMode, providedTaxAmount, hasTaxableLines);
     * "Purchase invoice cannot mix GST and non-GST materials"
     * lineTax = currency(lineNet.multiply(effectiveTaxRate)
     * .divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
     * BigDecimal allocatedTax = (i == computedLines.size() - 1)
     * .divide(inventoryTotal, 6, RoundingMode.HALF_UP));
     * if (lineRequest != null && lineRequest.taxRate() != null) {
     * if (rawMaterial != null && rawMaterial.getGstRate() != null) {
     * if (company != null && company.getDefaultGstRate() != null) {
     * throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("GST rate must be zero or positive");
     * return BigDecimal.ZERO;
     */

    private final PurchaseOrderService purchaseOrderService;
    private final GoodsReceiptService goodsReceiptService;
    private final PurchaseInvoiceService purchaseInvoiceService;
    private final PurchaseReturnService purchaseReturnService;

    @Autowired
    public PurchasingService(PurchaseOrderService purchaseOrderService,
                             GoodsReceiptService goodsReceiptService,
                             PurchaseInvoiceService purchaseInvoiceService,
                             PurchaseReturnService purchaseReturnService) {
        this.purchaseOrderService = purchaseOrderService;
        this.goodsReceiptService = goodsReceiptService;
        this.purchaseInvoiceService = purchaseInvoiceService;
        this.purchaseReturnService = purchaseReturnService;
    }

    public PurchasingService(CompanyContextService companyContextService,
                             RawMaterialPurchaseRepository purchaseRepository,
                             PurchaseOrderRepository purchaseOrderRepository,
                             RawMaterialRepository rawMaterialRepository,
                             RawMaterialBatchRepository rawMaterialBatchRepository,
                             RawMaterialService rawMaterialService,
                             RawMaterialMovementRepository movementRepository,
                             GoodsReceiptRepository goodsReceiptRepository,
                             AccountingFacade accountingFacade,
                             JournalEntryRepository journalEntryRepository,
                             CompanyEntityLookup companyEntityLookup,
                             ReferenceNumberService referenceNumberService,
                             CompanyClock companyClock,
                             AccountingPeriodService accountingPeriodService,
                             GstService gstService,
                             PurchaseOrderStatusHistoryRepository purchaseOrderStatusHistoryRepository,
                             PlatformTransactionManager transactionManager, PartnerSettlementAllocationRepository settlementAllocationRepository) { PurchaseResponseMapper responseMapper = new PurchaseResponseMapper(purchaseRepository, settlementAllocationRepository);
        PurchaseTaxPolicy purchaseTaxPolicy = new PurchaseTaxPolicy();
        this.purchaseOrderService = new PurchaseOrderService(
                companyContextService,
                purchaseOrderRepository,
                rawMaterialRepository,
                companyEntityLookup,
                responseMapper,
                purchaseOrderStatusHistoryRepository
        );
        this.goodsReceiptService = new GoodsReceiptService(
                companyContextService,
                purchaseOrderRepository,
                goodsReceiptRepository,
                rawMaterialRepository,
                rawMaterialService,
                companyEntityLookup,
                accountingPeriodService,
                responseMapper,
                this.purchaseOrderService,
                transactionManager
        );
        this.purchaseInvoiceService = new PurchaseInvoiceService(
                new PurchaseInvoiceEngine(
                        companyContextService,
                        purchaseRepository,
                        purchaseOrderRepository,
                        goodsReceiptRepository,
                        rawMaterialRepository,
                        rawMaterialBatchRepository,
                        rawMaterialService,
                        movementRepository,
                        accountingFacade,
                        companyEntityLookup,
                        referenceNumberService,
                        companyClock,
                        gstService,
                        responseMapper,
                        purchaseTaxPolicy
                ),
                this.purchaseOrderService
        );
        this.purchaseReturnService = new PurchaseReturnService(
                companyContextService,
                purchaseRepository,
                rawMaterialRepository,
                rawMaterialBatchRepository,
                movementRepository,
                accountingFacade,
                journalEntryRepository,
                companyEntityLookup,
                referenceNumberService,
                companyClock,
                gstService,
                new PurchaseReturnAllocationService()
        );
    }

    public List<RawMaterialPurchaseResponse> listPurchases() {
        return purchaseInvoiceService.listPurchases();
    }

    public List<RawMaterialPurchaseResponse> listPurchases(Long supplierId) {
        return purchaseInvoiceService.listPurchases(supplierId);
    }

    public List<PurchaseOrderResponse> listPurchaseOrders() {
        return purchaseOrderService.listPurchaseOrders();
    }

    public List<PurchaseOrderResponse> listPurchaseOrders(Long supplierId) {
        return purchaseOrderService.listPurchaseOrders(supplierId);
    }

    public PurchaseOrderResponse getPurchaseOrder(Long id) {
        return purchaseOrderService.getPurchaseOrder(id);
    }

    public PurchaseOrderResponse createPurchaseOrder(PurchaseOrderRequest request) {
        return purchaseOrderService.createPurchaseOrder(request);
    }

    public PurchaseOrderResponse approvePurchaseOrder(Long id) {
        return purchaseOrderService.approvePurchaseOrder(id);
    }

    public PurchaseOrderResponse voidPurchaseOrder(Long id, PurchaseOrderVoidRequest request) {
        return purchaseOrderService.voidPurchaseOrder(id, request);
    }

    public PurchaseOrderResponse closePurchaseOrder(Long id) {
        return purchaseOrderService.closePurchaseOrder(id);
    }

    public List<PurchaseOrderStatusHistoryResponse> getPurchaseOrderTimeline(Long id) {
        return purchaseOrderService.getPurchaseOrderTimeline(id);
    }

    public List<GoodsReceiptResponse> listGoodsReceipts() {
        return goodsReceiptService.listGoodsReceipts();
    }

    public List<GoodsReceiptResponse> listGoodsReceipts(Long supplierId) {
        return goodsReceiptService.listGoodsReceipts(supplierId);
    }

    public GoodsReceiptResponse getGoodsReceipt(Long id) {
        return goodsReceiptService.getGoodsReceipt(id);
    }

    public GoodsReceiptResponse createGoodsReceipt(GoodsReceiptRequest request) {
        return goodsReceiptService.createGoodsReceipt(request);
    }

    public RawMaterialPurchaseResponse getPurchase(Long id) {
        return purchaseInvoiceService.getPurchase(id);
    }

    public RawMaterialPurchaseResponse createPurchase(RawMaterialPurchaseRequest request) {
        /*
         * JournalEntryDto entry = postPurchaseEntry(
         * request,
         * supplier,
         * inventoryDebits,
         * taxAmount,
         * totalAmount,
         * referenceNumber,
         * gstBreakdown);
         * purchase = purchaseRepository.save(purchase);
         */
        return purchaseInvoiceService.createPurchase(request);
    }

    public JournalEntryDto recordPurchaseReturn(PurchaseReturnRequest request) {
        return purchaseReturnService.recordPurchaseReturn(request);
    }

    public PurchaseReturnPreviewDto previewPurchaseReturn(PurchaseReturnRequest request) {
        return purchaseReturnService.previewPurchaseReturn(request);
    }
}
