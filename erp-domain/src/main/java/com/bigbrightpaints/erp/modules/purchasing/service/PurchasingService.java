package com.bigbrightpaints.erp.modules.purchasing.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.service.RawMaterialService;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceipt;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrder;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptLineResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderLineResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseLineResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnRequest;
import jakarta.transaction.Transactional;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class PurchasingService {

    private static final BigDecimal MAX_GST_RATE = new BigDecimal("28.00");
    private static final BigDecimal UNIT_COST_TOLERANCE = new BigDecimal("0.01");
    private static final BigDecimal QUANTITY_TOLERANCE = new BigDecimal("0.0001");
    private static final String WORKFLOW_PURCHASE_RETURN = "purchase_return";
    private static final String REASON_PURCHASE_STATUS_TERMINAL = "PURCHASE_STATUS_TERMINAL";
    private static final String REASON_ON_HAND_STOCK_INSUFFICIENT = "ON_HAND_STOCK_INSUFFICIENT";
    private static final String REASON_BATCH_STOCK_CONFLICT = "BATCH_STOCK_CONFLICT";
    private static final String REASON_BATCH_STOCK_INSUFFICIENT = "BATCH_STOCK_INSUFFICIENT";

    private final CompanyContextService companyContextService;
    private final RawMaterialPurchaseRepository purchaseRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;
    private final RawMaterialRepository rawMaterialRepository;
    private final RawMaterialBatchRepository rawMaterialBatchRepository;
    private final RawMaterialService rawMaterialService;
    private final RawMaterialMovementRepository movementRepository;
    private final AccountingFacade accountingFacade;
    private final JournalEntryRepository journalEntryRepository;
    private final CompanyEntityLookup companyEntityLookup;
    private final ReferenceNumberService referenceNumberService;
    private final CompanyClock companyClock;
    private final AccountingPeriodService accountingPeriodService;
    private final TransactionTemplate transactionTemplate;

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
                             PlatformTransactionManager transactionManager) {
        this.companyContextService = companyContextService;
        this.purchaseRepository = purchaseRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.rawMaterialRepository = rawMaterialRepository;
        this.rawMaterialBatchRepository = rawMaterialBatchRepository;
        this.rawMaterialService = rawMaterialService;
        this.movementRepository = movementRepository;
        this.goodsReceiptRepository = goodsReceiptRepository;
        this.accountingFacade = accountingFacade;
        this.journalEntryRepository = journalEntryRepository;
        this.companyEntityLookup = companyEntityLookup;
        this.referenceNumberService = referenceNumberService;
        this.companyClock = companyClock;
        this.accountingPeriodService = accountingPeriodService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<RawMaterialPurchaseResponse> listPurchases() {
        return listPurchases(null);
    }

    public List<RawMaterialPurchaseResponse> listPurchases(Long supplierId) {
        Company company = companyContextService.requireCurrentCompany();
        Supplier supplier = supplierId != null ? companyEntityLookup.requireSupplier(company, supplierId) : null;
        List<RawMaterialPurchase> purchases = supplier == null
                ? purchaseRepository.findByCompanyWithLinesOrderByInvoiceDateDesc(company)
                : purchaseRepository.findByCompanyAndSupplierWithLinesOrderByInvoiceDateDesc(company, supplier);
        return purchases.stream()
                .map(this::toResponse)
                .toList();
    }

    public List<PurchaseOrderResponse> listPurchaseOrders() {
        return listPurchaseOrders(null);
    }

    public List<PurchaseOrderResponse> listPurchaseOrders(Long supplierId) {
        Company company = companyContextService.requireCurrentCompany();
        Supplier supplier = supplierId != null ? companyEntityLookup.requireSupplier(company, supplierId) : null;
        List<PurchaseOrder> orders = supplier == null
                ? purchaseOrderRepository.findByCompanyWithLinesOrderByOrderDateDesc(company)
                : purchaseOrderRepository.findByCompanyAndSupplierWithLinesOrderByOrderDateDesc(company, supplier);
        return orders.stream()
                .map(this::toPurchaseOrderResponse)
                .toList();
    }

    public PurchaseOrderResponse getPurchaseOrder(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        PurchaseOrder order = purchaseOrderRepository.findByCompanyAndId(company, id)
                .orElseThrow(() -> new IllegalArgumentException("Purchase order not found"));
        return toPurchaseOrderResponse(order);
    }

    @Transactional
    public PurchaseOrderResponse createPurchaseOrder(PurchaseOrderRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Supplier supplier = companyEntityLookup.requireSupplier(company, request.supplierId());

        String orderNumber = request.orderNumber().trim();
        purchaseOrderRepository.lockByCompanyAndOrderNumberIgnoreCase(company, orderNumber)
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Order number already used for this company");
                });

        List<PurchaseOrderLineRequest> sortedLines = request.lines().stream()
                .sorted(Comparator.comparing(PurchaseOrderLineRequest::rawMaterialId))
                .toList();

        Map<Long, RawMaterial> lockedMaterials = new HashMap<>();
        Set<Long> seenMaterialIds = new HashSet<>();
        for (PurchaseOrderLineRequest lineRequest : sortedLines) {
            RawMaterial rawMaterial = requireMaterial(company, lineRequest.rawMaterialId());
            if (!seenMaterialIds.add(rawMaterial.getId())) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Purchase order has duplicate raw material lines")
                        .withDetail("rawMaterialId", rawMaterial.getId());
            }
            lockedMaterials.put(rawMaterial.getId(), rawMaterial);
        }

        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setCompany(company);
        purchaseOrder.setSupplier(supplier);
        purchaseOrder.setOrderNumber(orderNumber);
        purchaseOrder.setOrderDate(request.orderDate());
        purchaseOrder.setMemo(clean(request.memo()));

        for (PurchaseOrderLineRequest lineRequest : request.lines()) {
            RawMaterial rawMaterial = lockedMaterials.get(lineRequest.rawMaterialId());
            if (rawMaterial == null) {
                throw new IllegalArgumentException("Raw material not found");
            }
            BigDecimal quantity = positive(lineRequest.quantity(), "quantity");
            BigDecimal costPerUnit = positive(lineRequest.costPerUnit(), "costPerUnit");
            String unit = StringUtils.hasText(lineRequest.unit())
                    ? lineRequest.unit().trim()
                    : rawMaterial.getUnitType();
            BigDecimal lineTotal = currency(MoneyUtils.safeMultiply(quantity, costPerUnit));

            PurchaseOrderLine line = new PurchaseOrderLine();
            line.setPurchaseOrder(purchaseOrder);
            line.setRawMaterial(rawMaterial);
            line.setQuantity(quantity);
            line.setUnit(unit);
            line.setCostPerUnit(costPerUnit);
            line.setLineTotal(lineTotal);
            line.setNotes(clean(lineRequest.notes()));
            purchaseOrder.getLines().add(line);
        }

        PurchaseOrder saved = purchaseOrderRepository.save(purchaseOrder);
        return toPurchaseOrderResponse(saved);
    }

    public List<GoodsReceiptResponse> listGoodsReceipts() {
        return listGoodsReceipts(null);
    }

    public List<GoodsReceiptResponse> listGoodsReceipts(Long supplierId) {
        Company company = companyContextService.requireCurrentCompany();
        Supplier supplier = supplierId != null ? companyEntityLookup.requireSupplier(company, supplierId) : null;
        List<GoodsReceipt> receipts = supplier == null
                ? goodsReceiptRepository.findByCompanyWithLinesOrderByReceiptDateDesc(company)
                : goodsReceiptRepository.findByCompanyAndSupplierWithLinesOrderByReceiptDateDesc(company, supplier);
        return receipts.stream()
                .map(this::toGoodsReceiptResponse)
                .toList();
    }

    public GoodsReceiptResponse getGoodsReceipt(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        GoodsReceipt receipt = goodsReceiptRepository.findByCompanyAndId(company, id)
                .orElseThrow(() -> new IllegalArgumentException("Goods receipt not found"));
        return toGoodsReceiptResponse(receipt);
    }

    public GoodsReceiptResponse createGoodsReceipt(GoodsReceiptRequest request) {
        if (request == null || request.lines() == null || request.lines().isEmpty()) {
            throw new IllegalArgumentException("Goods receipt lines are required");
        }
        Company company = companyContextService.requireCurrentCompany();
        String idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey());
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Idempotency key is required for goods receipts");
        }
        List<GoodsReceiptLineRequest> sortedLines = request.lines().stream()
                .sorted(Comparator.comparing(GoodsReceiptLineRequest::rawMaterialId))
                .toList();
        String requestSignature = buildGoodsReceiptSignature(request, sortedLines);
        GoodsReceipt existing = goodsReceiptRepository.findWithLinesByCompanyAndIdempotencyKey(company, idempotencyKey)
                .orElse(null);
        if (existing != null) {
            assertIdempotencyMatch(existing, requestSignature, idempotencyKey);
            return toGoodsReceiptResponse(existing);
        }

        LocalDate receiptDate = request.receiptDate();
        if (receiptDate == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Receipt date is required");
        }
        try {
            return transactionTemplate.execute(status ->
                    {
                        accountingPeriodService.requireOpenPeriod(company, receiptDate);
                        return createGoodsReceiptInternal(request, company, idempotencyKey, requestSignature);
                    });
        } catch (RuntimeException ex) {
            if (!isDataIntegrityViolation(ex)) {
                throw ex;
            }
            GoodsReceipt concurrent = goodsReceiptRepository.findWithLinesByCompanyAndIdempotencyKey(company, idempotencyKey)
                    .orElseThrow(() -> ex);
            assertIdempotencyMatch(concurrent, requestSignature, idempotencyKey);
            return toGoodsReceiptResponse(concurrent);
        }
    }

    private GoodsReceiptResponse createGoodsReceiptInternal(GoodsReceiptRequest request,
                                                           Company company,
                                                           String idempotencyKey,
                                                           String requestSignature) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.lockByCompanyAndId(company, request.purchaseOrderId())
                .orElseThrow(() -> new IllegalArgumentException("Purchase order not found"));
        Supplier supplier = purchaseOrder.getSupplier();

        String receiptNumber = request.receiptNumber().trim();
        goodsReceiptRepository.lockByCompanyAndReceiptNumberIgnoreCase(company, receiptNumber)
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Receipt number already used for this company");
                });
        if ("CLOSED".equalsIgnoreCase(purchaseOrder.getStatus())) {
            throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                    "Purchase order is already closed")
                    .withDetail("purchaseOrderId", purchaseOrder.getId());
        }

        Map<Long, PurchaseOrderLine> orderLinesByMaterial = new HashMap<>();
        for (PurchaseOrderLine line : purchaseOrder.getLines()) {
            if (line.getRawMaterial() == null || line.getRawMaterial().getId() == null) {
                continue;
            }
            Long materialId = line.getRawMaterial().getId();
            if (orderLinesByMaterial.containsKey(materialId)) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Purchase order has duplicate raw material lines")
                        .withDetail("rawMaterialId", materialId);
            }
            orderLinesByMaterial.put(materialId, line);
        }

        Map<Long, BigDecimal> alreadyReceived = new HashMap<>();
        for (GoodsReceipt existingReceipt : goodsReceiptRepository.findByPurchaseOrder(purchaseOrder)) {
            for (GoodsReceiptLine existingLine : existingReceipt.getLines()) {
                RawMaterial existingMaterial = existingLine.getRawMaterial();
                if (existingMaterial == null || existingMaterial.getId() == null) {
                    continue;
                }
                alreadyReceived.merge(existingMaterial.getId(), existingLine.getQuantity(), BigDecimal::add);
            }
        }
        boolean hasRemaining = false;
        for (Map.Entry<Long, PurchaseOrderLine> entry : orderLinesByMaterial.entrySet()) {
            BigDecimal ordered = entry.getValue().getQuantity();
            BigDecimal received = alreadyReceived.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            if (ordered.subtract(received).compareTo(BigDecimal.ZERO) > 0) {
                hasRemaining = true;
                break;
            }
        }
        if (!hasRemaining) {
            throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                    "Purchase order already fully received")
                    .withDetail("purchaseOrderId", purchaseOrder.getId());
        }

        GoodsReceipt receipt = new GoodsReceipt();
        receipt.setCompany(company);
        receipt.setSupplier(supplier);
        receipt.setPurchaseOrder(purchaseOrder);
        receipt.setReceiptNumber(receiptNumber);
        receipt.setReceiptDate(request.receiptDate());
        receipt.setMemo(clean(request.memo()));
        receipt.setIdempotencyKey(idempotencyKey);
        receipt.setIdempotencyHash(requestSignature);

        boolean fullyReceived = true;
        Set<Long> receiptMaterialIds = new HashSet<>();
        Map<Long, BigDecimal> receiptQuantities = new HashMap<>();
        for (GoodsReceiptLineRequest lineRequest : request.lines()) {
            RawMaterial rawMaterial = requireMaterial(company, lineRequest.rawMaterialId());
            PurchaseOrderLine orderLine = orderLinesByMaterial.get(rawMaterial.getId());
            if (orderLine == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Goods receipt line is not covered by purchase order")
                        .withDetail("rawMaterialId", rawMaterial.getId());
            }
            if (!receiptMaterialIds.add(rawMaterial.getId())) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Goods receipt has duplicate raw material lines")
                        .withDetail("rawMaterialId", rawMaterial.getId());
            }
            BigDecimal quantity = positive(lineRequest.quantity(), "quantity");
            BigDecimal alreadyQty = alreadyReceived.getOrDefault(rawMaterial.getId(), BigDecimal.ZERO);
            BigDecimal remainingQty = orderLine.getQuantity().subtract(alreadyQty);
            if (remainingQty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                        "Raw material already fully received for this purchase order")
                        .withDetail("rawMaterialId", rawMaterial.getId());
            }
            if (quantity.compareTo(remainingQty) > 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Goods receipt quantity exceeds remaining purchase order quantity")
                        .withDetail("rawMaterialId", rawMaterial.getId())
                        .withDetail("orderQuantity", orderLine.getQuantity())
                        .withDetail("alreadyReceivedQuantity", alreadyQty)
                        .withDetail("remainingQuantity", remainingQty)
                        .withDetail("receiptQuantity", quantity);
            }
            receiptQuantities.put(rawMaterial.getId(), quantity);
            if (quantity.add(alreadyQty).compareTo(orderLine.getQuantity()) != 0) {
                fullyReceived = false;
            }

            BigDecimal costPerUnit = positive(lineRequest.costPerUnit(), "costPerUnit");
            String requestedUnit = StringUtils.hasText(lineRequest.unit()) ? lineRequest.unit().trim() : null;
            if (requestedUnit != null && orderLine.getUnit() != null
                    && !orderLine.getUnit().equalsIgnoreCase(requestedUnit)) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Goods receipt unit must match purchase order unit")
                        .withDetail("rawMaterialId", rawMaterial.getId())
                        .withDetail("orderUnit", orderLine.getUnit())
                        .withDetail("receiptUnit", requestedUnit);
            }
            String unit = requestedUnit != null ? requestedUnit : orderLine.getUnit();
            String batchCode = StringUtils.hasText(lineRequest.batchCode())
                    ? lineRequest.batchCode().trim()
                    : null;
            BigDecimal lineTotal = currency(MoneyUtils.safeMultiply(quantity, costPerUnit));

            GoodsReceiptLine line = new GoodsReceiptLine();
            line.setGoodsReceipt(receipt);
            line.setRawMaterial(rawMaterial);
            line.setBatchCode(batchCode != null ? batchCode : rawMaterial.getSku() + "-" + receiptNumber);
            line.setQuantity(quantity);
            line.setUnit(unit);
            line.setCostPerUnit(costPerUnit);
            line.setLineTotal(lineTotal);
            line.setNotes(clean(lineRequest.notes()));
            receipt.getLines().add(line);
        }

        for (Map.Entry<Long, PurchaseOrderLine> entry : orderLinesByMaterial.entrySet()) {
            BigDecimal totalReceived = alreadyReceived.getOrDefault(entry.getKey(), BigDecimal.ZERO)
                    .add(receiptQuantities.getOrDefault(entry.getKey(), BigDecimal.ZERO));
            if (totalReceived.compareTo(entry.getValue().getQuantity()) != 0) {
                fullyReceived = false;
            }
        }

        if (!fullyReceived) {
            receipt.setStatus("PARTIAL");
            purchaseOrder.setStatus("PARTIAL");
        } else {
            purchaseOrder.setStatus("RECEIVED");
        }

        for (GoodsReceiptLine line : receipt.getLines()) {
            RawMaterial rawMaterial = line.getRawMaterial();
            RawMaterialBatchRequest batchRequest = new RawMaterialBatchRequest(
                    line.getBatchCode(),
                    line.getQuantity(),
                    line.getUnit(),
                    line.getCostPerUnit(),
                    supplier.getId(),
                    line.getNotes()
            );
            RawMaterialService.ReceiptContext context = new RawMaterialService.ReceiptContext(
                    InventoryReference.RAW_MATERIAL_PURCHASE,
                    receiptNumber,
                    "Goods receipt " + receiptNumber,
                    false
            );
            RawMaterialService.ReceiptResult receiptResult = rawMaterialService.recordReceipt(rawMaterial.getId(), batchRequest, context);
            line.setRawMaterialBatch(receiptResult.batch());
            line.setBatchCode(receiptResult.batch().getBatchCode());
        }

        GoodsReceipt savedReceipt = goodsReceiptRepository.saveAndFlush(receipt);
        purchaseOrderRepository.save(purchaseOrder);
        return toGoodsReceiptResponse(savedReceipt);
    }

    public RawMaterialPurchaseResponse getPurchase(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        RawMaterialPurchase purchase = companyEntityLookup.requireRawMaterialPurchase(company, id);
        return toResponse(purchase);
    }

    @Transactional
    public RawMaterialPurchaseResponse createPurchase(RawMaterialPurchaseRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Supplier supplier = companyEntityLookup.requireSupplier(company, request.supplierId());
        if (supplier.getPayableAccount() == null) {
            throw new IllegalStateException("Supplier " + supplier.getName() + " is missing a payable account");
        }
        String invoiceNumber = request.invoiceNumber().trim();

        // Use pessimistic lock to prevent duplicate invoice race condition
        purchaseRepository.lockByCompanyAndInvoiceNumberIgnoreCase(company, invoiceNumber)
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Invoice number already used for this company");
                });

        GoodsReceipt goodsReceipt = goodsReceiptRepository.lockByCompanyAndId(company, request.goodsReceiptId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Goods receipt not found"));
        if (!supplier.getId().equals(goodsReceipt.getSupplier().getId())) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Goods receipt does not belong to the supplier");
        }
        if ("INVOICED".equalsIgnoreCase(goodsReceipt.getStatus())) {
            throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                    "Goods receipt is already invoiced");
        }
        PurchaseOrder purchaseOrder = goodsReceipt.getPurchaseOrder();
        if (request.purchaseOrderId() != null
                && (purchaseOrder == null || !purchaseOrder.getId().equals(request.purchaseOrderId()))) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Purchase order does not match goods receipt");
        }
        if (purchaseOrder == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Goods receipt is missing a purchase order");
        }
        purchaseRepository.findByCompanyAndGoodsReceipt(company, goodsReceipt)
                .ifPresent(existing -> {
                    throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                            "Goods receipt already linked to a purchase invoice")
                            .withDetail("purchaseId", existing.getId());
                });
        assertGoodsReceiptMovementsUnlinked(company, goodsReceipt.getReceiptNumber());

        Map<Long, BigDecimal> receiptQuantities = new HashMap<>();
        Map<Long, BigDecimal> receiptUnitCosts = new HashMap<>();
        Map<Long, String> receiptUnits = new HashMap<>();
        Map<Long, GoodsReceiptLine> receiptLinesByMaterial = new HashMap<>();
        for (GoodsReceiptLine line : goodsReceipt.getLines()) {
            if (line.getRawMaterial() == null || line.getRawMaterial().getId() == null) {
                continue;
            }
            Long materialId = line.getRawMaterial().getId();
            if (receiptQuantities.containsKey(materialId)) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Goods receipt has duplicate raw material lines")
                        .withDetail("rawMaterialId", materialId);
            }
            receiptQuantities.put(materialId, line.getQuantity());
            receiptUnitCosts.put(materialId, line.getCostPerUnit());
            receiptUnits.put(materialId, line.getUnit());
            receiptLinesByMaterial.put(materialId, line);
        }

        boolean taxProvided = request.taxAmount() != null;
        Set<Long> invoiceMaterialIds = new HashSet<>();

        // Sort lines by rawMaterialId to maintain consistent lock ordering and avoid deadlocks
        List<RawMaterialPurchaseLineRequest> sortedLines = request.lines().stream()
                .sorted(Comparator.comparing(RawMaterialPurchaseLineRequest::rawMaterialId))
                .toList();

        // Pre-validate and lock all materials in consistent order before any mutations
        Map<Long, RawMaterial> lockedMaterials = new HashMap<>();
        for (RawMaterialPurchaseLineRequest lineRequest : sortedLines) {
            RawMaterial rawMaterial = requireMaterial(company, lineRequest.rawMaterialId());
            lockedMaterials.put(rawMaterial.getId(), rawMaterial);
        }

        // Enforce single tax mode contract for downstream AP settlement/posting linkage.
        PurchaseTaxMode purchaseTaxMode = resolvePurchaseTaxMode(sortedLines, lockedMaterials);

        BigDecimal inventoryTotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        Map<Long, BigDecimal> inventoryDebits = new HashMap<>();
        List<PurchaseLineCalc> computedLines = new ArrayList<>();
        boolean hasTaxableLines = false;

        for (RawMaterialPurchaseLineRequest lineRequest : request.lines()) {
            RawMaterial rawMaterial = lockedMaterials.get(lineRequest.rawMaterialId());
            if (rawMaterial == null) {
                throw new IllegalArgumentException("Raw material not found");
            }
            BigDecimal quantity = positive(lineRequest.quantity(), "quantity");
            BigDecimal costPerUnit = positive(lineRequest.costPerUnit(), "costPerUnit");
            String unit = StringUtils.hasText(lineRequest.unit())
                    ? lineRequest.unit().trim()
                    : rawMaterial.getUnitType();
            String batchCode = StringUtils.hasText(lineRequest.batchCode())
                    ? lineRequest.batchCode().trim()
                    : null;

            BigDecimal receiptQty = receiptQuantities.get(rawMaterial.getId());
            if (receiptQty == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Purchase line is not covered by goods receipt")
                        .withDetail("rawMaterialId", rawMaterial.getId());
            }
            if (!invoiceMaterialIds.add(rawMaterial.getId())) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Purchase invoice has duplicate raw material lines")
                        .withDetail("rawMaterialId", rawMaterial.getId());
            }
            if (quantity.compareTo(receiptQty) != 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Purchase quantity must match goods receipt quantity")
                        .withDetail("rawMaterialId", rawMaterial.getId())
                        .withDetail("receiptQuantity", receiptQty)
                        .withDetail("invoiceQuantity", quantity);
            }
            BigDecimal receiptCost = receiptUnitCosts.get(rawMaterial.getId());
            if (receiptCost != null && !MoneyUtils.withinTolerance(receiptCost, costPerUnit, UNIT_COST_TOLERANCE)) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Purchase unit cost must match goods receipt cost")
                        .withDetail("rawMaterialId", rawMaterial.getId())
                        .withDetail("receiptCostPerUnit", receiptCost)
                        .withDetail("invoiceCostPerUnit", costPerUnit);
            }
            String receiptUnit = receiptUnits.get(rawMaterial.getId());
            if (receiptUnit != null && !receiptUnit.equalsIgnoreCase(unit)) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Purchase unit must match goods receipt unit")
                        .withDetail("rawMaterialId", rawMaterial.getId())
                        .withDetail("receiptUnit", receiptUnit)
                        .withDetail("invoiceUnit", unit);
            }
            BigDecimal lineGrossRaw = MoneyUtils.safeMultiply(quantity, costPerUnit);
            BigDecimal lineGross = taxProvided ? lineGrossRaw : currency(lineGrossRaw);
            BigDecimal lineNet = lineGross;
            BigDecimal lineTax = currency(BigDecimal.ZERO);
            BigDecimal lineTaxRate = null;
            BigDecimal netUnitCost = costPerUnit;
            BigDecimal effectiveTaxRate = resolveLineTaxRateForMode(lineRequest, rawMaterial, company, purchaseTaxMode);
            if (effectiveTaxRate.compareTo(BigDecimal.ZERO) > 0) {
                hasTaxableLines = true;
            }

            if (!taxProvided) {
                lineTaxRate = effectiveTaxRate;
                boolean taxInclusive = Boolean.TRUE.equals(lineRequest.taxInclusive());
                if (effectiveTaxRate.compareTo(BigDecimal.ZERO) > 0) {
                    if (taxInclusive) {
                        BigDecimal divisor = BigDecimal.ONE.add(
                                effectiveTaxRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
                        if (divisor.signum() > 0) {
                            BigDecimal net = lineGross.divide(divisor, 6, RoundingMode.HALF_UP);
                            lineNet = currency(net);
                            lineTax = currency(lineGross.subtract(lineNet));
                            netUnitCost = lineNet.divide(quantity, 6, RoundingMode.HALF_UP);
                        }
                    } else {
                        lineNet = lineGross;
                        lineTax = currency(lineNet.multiply(effectiveTaxRate)
                                .divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
                    }
                }
            }

            Long inventoryAccountId = rawMaterial.getInventoryAccountId();
            if (inventoryAccountId == null) {
                throw new IllegalStateException("Raw material " + rawMaterial.getName() + " is missing an inventory account");
            }

            inventoryTotal = inventoryTotal.add(lineNet);
            taxTotal = taxTotal.add(lineTax);
            inventoryDebits.merge(inventoryAccountId, lineNet, BigDecimal::add);
            computedLines.add(new PurchaseLineCalc(rawMaterial, quantity, unit, batchCode, netUnitCost, lineNet, lineTax, lineTaxRate, lineRequest.notes()));
        }

        if (invoiceMaterialIds.size() != receiptQuantities.size()) {
            List<Long> missingMaterialIds = receiptQuantities.keySet().stream()
                    .filter(materialId -> !invoiceMaterialIds.contains(materialId))
                    .sorted()
                    .toList();
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Purchase invoice must include all goods receipt lines")
                    .withDetail("missingRawMaterialIds", missingMaterialIds);
        }

        BigDecimal providedTaxAmount = taxProvided
                ? currency(nonNegative(request.taxAmount(), "taxAmount"))
                : null;
        enforcePurchaseTaxContract(purchaseTaxMode, providedTaxAmount, hasTaxableLines);
        BigDecimal taxAmount = taxProvided ? providedTaxAmount : currency(taxTotal);
        BigDecimal totalAmount = inventoryTotal.add(taxAmount);

        if (taxProvided && taxAmount.compareTo(BigDecimal.ZERO) > 0 && inventoryTotal.compareTo(BigDecimal.ZERO) > 0
                && !computedLines.isEmpty()) {
            List<PurchaseLineCalc> allocatedLines = new ArrayList<>(computedLines.size());
            BigDecimal remaining = taxAmount;
            for (int i = 0; i < computedLines.size(); i++) {
                PurchaseLineCalc line = computedLines.get(i);
                BigDecimal allocatedTax = (i == computedLines.size() - 1)
                        ? remaining
                        : currency(line.lineNet().multiply(taxAmount)
                                .divide(inventoryTotal, 6, RoundingMode.HALF_UP));
                remaining = remaining.subtract(allocatedTax);
                allocatedLines.add(new PurchaseLineCalc(
                        line.rawMaterial(),
                        line.quantity(),
                        line.unit(),
                        line.batchCode(),
                        line.netUnitCost(),
                        line.lineNet(),
                        allocatedTax,
                        null,
                        line.notes()));
            }
            computedLines = allocatedLines;
        }

        // Post journal FIRST to avoid orphan purchases if journal fails
        String referenceNumber = referenceNumberService.purchaseReference(company, supplier, invoiceNumber);
        JournalEntryDto entry = postPurchaseEntry(request, supplier, inventoryDebits, taxAmount, totalAmount, referenceNumber);
        JournalEntry linkedJournal = null;
        if (entry != null) {
            linkedJournal = companyEntityLookup.requireJournalEntry(company, entry.id());
        }

        // Now create purchase with journal already linked
        RawMaterialPurchase purchase = new RawMaterialPurchase();
        purchase.setCompany(company);
        purchase.setSupplier(supplier);
        purchase.setInvoiceNumber(invoiceNumber);
        purchase.setInvoiceDate(request.invoiceDate());
        purchase.setMemo(clean(request.memo()));
        purchase.setTotalAmount(totalAmount);
        purchase.setTaxAmount(taxAmount);
        purchase.setOutstandingAmount(totalAmount);
        purchase.setJournalEntry(linkedJournal);
        purchase.setPurchaseOrder(purchaseOrder);
        purchase.setGoodsReceipt(goodsReceipt);

        // Process lines using already-locked materials
        List<RawMaterialService.ReceiptResult> receipts = new ArrayList<>();
        int lineIndex = 1;
        for (PurchaseLineCalc lineCalc : computedLines) {
            RawMaterial rawMaterial = lineCalc.rawMaterial();
            BigDecimal quantity = lineCalc.quantity();
            BigDecimal costPerUnit = lineCalc.netUnitCost();
            String unit = lineCalc.unit();
            String batchCode = lineCalc.batchCode();
            BigDecimal lineTotal = lineCalc.lineNet();

            GoodsReceiptLine receiptLine = receiptLinesByMaterial.get(rawMaterial.getId());
            RawMaterialBatch batch = receiptLine != null ? receiptLine.getRawMaterialBatch() : null;
            String lineReference = invoiceReference(invoiceNumber, lineIndex++);
            if (batch == null) {
                if (goodsReceipt != null) {
                    throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                            "Goods receipt line is missing batch; invoice cannot create inventory movements")
                            .withDetail("rawMaterialId", rawMaterial.getId())
                            .withDetail("goodsReceiptId", goodsReceipt.getId());
                }
                RawMaterialBatchRequest batchRequest = new RawMaterialBatchRequest(
                        batchCode,
                        quantity,
                        unit,
                        costPerUnit,
                        supplier.getId(),
                        lineCalc.notes()
                );
                RawMaterialService.ReceiptContext context = new RawMaterialService.ReceiptContext(
                        InventoryReference.RAW_MATERIAL_PURCHASE,
                        lineReference,
                        purchaseMemo(request.memo(), invoiceNumber, batchCode),
                        false
                );
                RawMaterialService.ReceiptResult receipt = rawMaterialService.recordReceipt(rawMaterial.getId(), batchRequest, context);
                receipts.add(receipt);
                batch = receipt.batch();
                if (receiptLine != null) {
                    receiptLine.setRawMaterialBatch(batch);
                    receiptLine.setBatchCode(batch.getBatchCode());
                }
            }

            RawMaterialPurchaseLine line = new RawMaterialPurchaseLine();
            line.setPurchase(purchase);
            line.setRawMaterial(rawMaterial);
            line.setRawMaterialBatch(batch);
            line.setBatchCode(batch != null ? batch.getBatchCode() : batchCode);
            line.setQuantity(quantity);
            line.setUnit(unit);
            line.setCostPerUnit(costPerUnit);
            line.setLineTotal(lineTotal);
            line.setTaxAmount(lineCalc.lineTax());
            line.setTaxRate(lineCalc.taxRate());
            line.setNotes(lineCalc.notes());
            purchase.getLines().add(line);
        }

        purchase = purchaseRepository.save(purchase);

        // Link journal entry to movements
        if (entry != null) {
            Long entryId = entry.id();
            receipts.stream()
                    .map(RawMaterialService.ReceiptResult::movement)
                    .filter(movement -> movement != null)
                    .forEach(movement -> {
                        movement.setJournalEntryId(entryId);
                        movementRepository.save(movement);
                    });
            linkGoodsReceiptMovementsToJournal(company, goodsReceipt.getReceiptNumber(), entryId);
        }
        goodsReceipt.setStatus("INVOICED");
        goodsReceiptRepository.save(goodsReceipt);
        if ("RECEIVED".equalsIgnoreCase(purchaseOrder.getStatus())) {
            boolean allInvoiced = goodsReceiptRepository.findByPurchaseOrder(purchaseOrder).stream()
                    .allMatch(gr -> "INVOICED".equalsIgnoreCase(gr.getStatus()));
            if (allInvoiced) {
                purchaseOrder.setStatus("CLOSED");
                purchaseOrderRepository.save(purchaseOrder);
            }
        }
        return toResponse(purchase);
    }

    private void assertGoodsReceiptMovementsUnlinked(Company company, String receiptNumber) {
        if (!StringUtils.hasText(receiptNumber)) {
            return;
        }
        for (RawMaterialMovement movement : findGoodsReceiptMovements(company, receiptNumber)) {
            Long existingJournalId = movement.getJournalEntryId();
            if (existingJournalId != null) {
                throw new ApplicationException(
                        ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                        "Goods receipt " + receiptNumber + " already linked to journal " + existingJournalId)
                        .withDetail("goodsReceiptNumber", receiptNumber)
                        .withDetail("movementId", movement.getId())
                        .withDetail("existingJournalEntryId", existingJournalId);
            }
        }
    }

    private void linkGoodsReceiptMovementsToJournal(Company company, String receiptNumber, Long journalEntryId) {
        if (journalEntryId == null || !StringUtils.hasText(receiptNumber)) {
            return;
        }
        List<RawMaterialMovement> receiptMovements = findGoodsReceiptMovements(company, receiptNumber);
        if (receiptMovements.isEmpty()) {
            return;
        }
        List<RawMaterialMovement> toUpdate = new ArrayList<>();
        for (RawMaterialMovement movement : receiptMovements) {
            Long existingJournalId = movement.getJournalEntryId();
            if (existingJournalId == null) {
                movement.setJournalEntryId(journalEntryId);
                toUpdate.add(movement);
                continue;
            }
            if (!Objects.equals(existingJournalId, journalEntryId)) {
                throw new ApplicationException(
                        ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                        "Goods receipt " + receiptNumber + " already linked to journal " + existingJournalId)
                        .withDetail("goodsReceiptNumber", receiptNumber)
                        .withDetail("movementId", movement.getId())
                        .withDetail("existingJournalEntryId", existingJournalId)
                        .withDetail("requestedJournalEntryId", journalEntryId);
            }
        }
        if (!toUpdate.isEmpty()) {
            movementRepository.saveAll(toUpdate);
        }
    }

    private List<RawMaterialMovement> findGoodsReceiptMovements(Company company, String receiptNumber) {
        List<RawMaterialMovement> movements = movementRepository
                .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                        company,
                        InventoryReference.RAW_MATERIAL_PURCHASE,
                        receiptNumber);
        return movements != null ? movements : List.of();
    }

    @Transactional
    public JournalEntryDto recordPurchaseReturn(PurchaseReturnRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Supplier supplier = companyEntityLookup.requireSupplier(company, request.supplierId());
        if (supplier.getPayableAccount() == null) {
            throw new IllegalStateException("Supplier " + supplier.getName() + " is missing a payable account");
        }
        RawMaterialPurchase purchase = purchaseRepository.lockByCompanyAndId(company, request.purchaseId())
                .orElseThrow(() -> new IllegalArgumentException("Raw material purchase not found"));
        assertPurchaseReturnAllowed(purchase);
        if (purchase.getSupplier() == null || !purchase.getSupplier().getId().equals(supplier.getId())) {
            throw new IllegalArgumentException("Purchase does not belong to the supplier");
        }
        RawMaterial material = rawMaterialRepository.lockByCompanyAndId(company, request.rawMaterialId())
                .orElseThrow(() -> new IllegalArgumentException("Raw material not found"));
        boolean materialInPurchase = purchase.getLines().stream()
                .anyMatch(line -> line.getRawMaterial() != null
                        && line.getRawMaterial().getId().equals(material.getId()));
        if (!materialInPurchase) {
            throw new IllegalArgumentException("Purchase does not include raw material " + material.getName());
        }
        if (material.getInventoryAccountId() == null) {
            throw new IllegalStateException("Raw material " + material.getName() + " is missing an inventory account mapping");
        }
        BigDecimal quantity = positive(request.quantity(), "quantity");
        BigDecimal unitCost = positive(request.unitCost(), "unitCost");
        BigDecimal lineNet = currency(MoneyUtils.safeMultiply(quantity, unitCost));
        BigDecimal taxAmount = computeReturnTax(purchase, material, quantity);
        BigDecimal totalAmount = currency(lineNet.add(taxAmount));
        String memo = returnMemo(material, supplier, request.reason());
        String reference = StringUtils.hasText(request.referenceNumber())
                ? request.referenceNumber().trim()
                : referenceNumberService.purchaseReturnReference(company, supplier);
        LocalDate returnDate = request.returnDate() != null ? request.returnDate() : companyClock.today(company);
        List<RawMaterialMovement> existingMovements = movementRepository
                .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(company,
                        InventoryReference.PURCHASE_RETURN,
                        reference);
        if (!existingMovements.isEmpty()) {
            return returnExistingPurchaseReturn(purchase, material, supplier, quantity, unitCost, reference,
                    returnDate, memo, existingMovements);
        }
        BigDecimal remainingReturnableQty = remainingReturnableQuantity(purchase, material);
        if (remainingReturnableQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "All purchased quantity has already been returned for this material")
                    .withDetail("purchaseId", purchase.getId())
                    .withDetail("rawMaterialId", material.getId());
        }
        if (quantity.compareTo(remainingReturnableQty) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Purchase return quantity exceeds remaining returnable quantity")
                    .withDetail("purchaseId", purchase.getId())
                    .withDetail("rawMaterialId", material.getId())
                    .withDetail("remainingReturnableQuantity", remainingReturnableQty)
                    .withDetail("requestedQuantity", quantity);
        }

        // Post journal FIRST before deducting stock
        Map<Long, BigDecimal> taxCredits = null;
        if (taxAmount.compareTo(BigDecimal.ZERO) > 0) {
            taxCredits = new HashMap<>();
            taxCredits.put(null, taxAmount);
        }
        JournalEntryDto entry = accountingFacade.postPurchaseReturn(
                supplier.getId(),
                reference,
                returnDate,
                memo,
                Map.of(material.getInventoryAccountId(), lineNet),
                taxCredits,
                totalAmount
        );

        // Use atomic UPDATE to prevent negative stock under concurrent access
        int updated = rawMaterialRepository.deductStockIfSufficient(material.getId(), quantity);
        if (updated == 0) {
            throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                    "Cannot return more than on-hand inventory for " + material.getName())
                    .withDetail("reasonCode", REASON_ON_HAND_STOCK_INSUFFICIENT)
                    .withDetail("workflow", WORKFLOW_PURCHASE_RETURN)
                    .withDetail("purchaseId", purchase.getId())
                    .withDetail("rawMaterialId", material.getId())
                    .withDetail("requestedQuantity", quantity);
        }

        List<RawMaterialMovement> movements = issueReturnFromBatches(material, quantity, unitCost, reference, entry.id());
        movementRepository.saveAll(movements);
        applyPurchaseReturnQuantity(purchase, material, quantity);
        applyPurchaseReturnToOutstanding(purchase, totalAmount);
        return entry;
    }

    private JournalEntryDto returnExistingPurchaseReturn(RawMaterialPurchase purchase,
                                                         RawMaterial material,
                                                         Supplier supplier,
                                                         BigDecimal quantity,
                                                         BigDecimal unitCost,
                                                         String reference,
                                                         LocalDate returnDate,
                                                         String memo,
                                                         List<RawMaterialMovement> existingMovements) {
        validateReturnReplay(material, quantity, unitCost, reference, existingMovements);
        BigDecimal lineNet = currency(MoneyUtils.safeMultiply(quantity, unitCost));
        BigDecimal taxAmount = computeReturnTax(purchase, material, quantity);
        BigDecimal totalAmount = currency(lineNet.add(taxAmount));
        Map<Long, BigDecimal> taxCredits = null;
        if (taxAmount.compareTo(BigDecimal.ZERO) > 0) {
            taxCredits = new HashMap<>();
            taxCredits.put(null, taxAmount);
        }
        JournalEntryDto entry = accountingFacade.postPurchaseReturn(
                supplier.getId(),
                reference,
                returnDate,
                memo,
                Map.of(material.getInventoryAccountId(), lineNet),
                taxCredits,
                totalAmount
        );
        if (entry != null) {
            Long entryId = entry.id();
            boolean needsLink = existingMovements.stream()
                    .anyMatch(movement -> movement.getJournalEntryId() == null
                            || !movement.getJournalEntryId().equals(entryId));
            if (needsLink) {
                existingMovements.forEach(movement -> movement.setJournalEntryId(entryId));
                movementRepository.saveAll(existingMovements);
            }
        }
        return entry;
    }

    private void validateReturnReplay(RawMaterial material,
                                      BigDecimal quantity,
                                      BigDecimal unitCost,
                                      String reference,
                                      List<RawMaterialMovement> existingMovements) {
        List<Long> materialIds = existingMovements.stream()
                .map(movement -> movement.getRawMaterial().getId())
                .distinct()
                .toList();
        if (materialIds.size() != 1 || !materialIds.get(0).equals(material.getId())) {
            throwIdempotencyConflict(material, quantity, unitCost, reference);
        }
        BigDecimal existingQty = existingMovements.stream()
                .map(RawMaterialMovement::getQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (existingQty.compareTo(quantity) != 0) {
            throwIdempotencyConflict(material, quantity, unitCost, reference);
        }
        BigDecimal expectedCost = unitCost != null ? unitCost : BigDecimal.ZERO;
        boolean unitCostMismatch = existingMovements.stream()
                .map(RawMaterialMovement::getUnitCost)
                .filter(Objects::nonNull)
                .anyMatch(cost -> cost.compareTo(expectedCost) != 0);
        if (unitCostMismatch) {
            throwIdempotencyConflict(material, quantity, unitCost, reference);
        }
    }

    private void throwIdempotencyConflict(RawMaterial material,
                                          BigDecimal quantity,
                                          BigDecimal unitCost,
                                          String reference) {
        throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                "Purchase return reference already used with different payload")
                .withDetail("reference", reference)
                .withDetail("rawMaterialId", material.getId())
                .withDetail("quantity", quantity)
                .withDetail("unitCost", unitCost);
    }

    private List<RawMaterialMovement> issueReturnFromBatches(RawMaterial material,
                                                            BigDecimal quantity,
                                                            BigDecimal unitCost,
                                                            String reference,
                                                            Long journalEntryId) {
        List<RawMaterialBatch> batches = rawMaterialBatchRepository.findAvailableBatchesFIFO(material);
        BigDecimal remaining = quantity;
        List<RawMaterialMovement> movements = new ArrayList<>();

        for (RawMaterialBatch batch : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal available = batch.getQuantity() != null ? batch.getQuantity() : BigDecimal.ZERO;
            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal take = available.min(remaining);
            if (take.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            int updated = rawMaterialBatchRepository.deductQuantityIfSufficient(batch.getId(), take);
            if (updated == 0) {
                throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                        "Concurrent modification detected or insufficient quantity for batch " + batch.getBatchCode())
                        .withDetail("reasonCode", REASON_BATCH_STOCK_CONFLICT)
                        .withDetail("workflow", WORKFLOW_PURCHASE_RETURN)
                        .withDetail("batchId", batch.getId())
                        .withDetail("batchCode", batch.getBatchCode())
                        .withDetail("rawMaterialId", material.getId())
                        .withDetail("requestedQuantity", take);
            }

            RawMaterialMovement movement = new RawMaterialMovement();
            movement.setRawMaterial(material);
            movement.setRawMaterialBatch(batch);
            movement.setReferenceType(InventoryReference.PURCHASE_RETURN);
            movement.setReferenceId(reference);
            movement.setMovementType("RETURN");
            movement.setQuantity(take);
            movement.setUnitCost(unitCost);
            movement.setJournalEntryId(journalEntryId);
            movements.add(movement);

            remaining = remaining.subtract(take);
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                    "Insufficient batch availability for " + material.getName())
                    .withDetail("reasonCode", REASON_BATCH_STOCK_INSUFFICIENT)
                    .withDetail("workflow", WORKFLOW_PURCHASE_RETURN)
                    .withDetail("rawMaterialId", material.getId())
                    .withDetail("remainingQuantity", remaining);
        }
        return movements;
    }

    private void assertPurchaseReturnAllowed(RawMaterialPurchase purchase) {
        if (purchase == null) {
            return;
        }
        String status = purchase.getStatus();
        if (status != null && ("VOID".equalsIgnoreCase(status) || "REVERSED".equalsIgnoreCase(status))) {
            throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                    "Purchase status does not allow returns: " + status)
                    .withDetail("reasonCode", REASON_PURCHASE_STATUS_TERMINAL)
                    .withDetail("workflow", WORKFLOW_PURCHASE_RETURN)
                    .withDetail("purchaseId", purchase.getId())
                    .withDetail("purchaseStatus", status);
        }
    }

    private void applyPurchaseReturnToOutstanding(RawMaterialPurchase purchase, BigDecimal totalAmount) {
        if (purchase == null) {
            return;
        }
        BigDecimal amount = currency(totalAmount != null ? totalAmount : BigDecimal.ZERO);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal currentOutstanding = currency(MoneyUtils.zeroIfNull(purchase.getOutstandingAmount()));
        BigDecimal newOutstanding = currency(currentOutstanding.subtract(amount));
        purchase.setOutstandingAmount(newOutstanding);
        if (purchase.getOutstandingAmount().compareTo(BigDecimal.ZERO) == 0 && isPurchaseFullyReturned(purchase)) {
            purchase.setStatus("VOID");
        } else {
            updatePurchaseStatus(purchase);
        }
    }

    private BigDecimal remainingReturnableQuantity(RawMaterialPurchase purchase, RawMaterial material) {
        if (purchase == null || material == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal purchased = purchase.getLines().stream()
                .filter(line -> line.getRawMaterial() != null
                        && line.getRawMaterial().getId().equals(material.getId()))
                .map(line -> quantityValue(line.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal returned = purchase.getLines().stream()
                .filter(line -> line.getRawMaterial() != null
                        && line.getRawMaterial().getId().equals(material.getId()))
                .map(line -> quantityValue(line.getReturnedQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remaining = purchased.subtract(returned);
        if (remaining.compareTo(BigDecimal.ZERO) < 0 && remaining.abs().compareTo(QUANTITY_TOLERANCE) <= 0) {
            return BigDecimal.ZERO;
        }
        return remaining.max(BigDecimal.ZERO);
    }

    private void applyPurchaseReturnQuantity(RawMaterialPurchase purchase, RawMaterial material, BigDecimal quantity) {
        if (purchase == null || material == null || quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal remaining = quantity;
        for (RawMaterialPurchaseLine line : purchase.getLines()) {
            if (line.getRawMaterial() == null || !line.getRawMaterial().getId().equals(material.getId())) {
                continue;
            }
            BigDecimal lineQty = quantityValue(line.getQuantity());
            BigDecimal alreadyReturned = quantityValue(line.getReturnedQuantity());
            BigDecimal available = lineQty.subtract(alreadyReturned);
            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal applied = available.min(remaining);
            line.setReturnedQuantity(alreadyReturned.add(applied));
            remaining = remaining.subtract(applied);
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
        }
        if (remaining.compareTo(QUANTITY_TOLERANCE) > 0) {
            throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                    "Purchase return quantity exceeds available returnable quantity after allocation")
                    .withDetail("purchaseId", purchase.getId())
                    .withDetail("rawMaterialId", material.getId())
                    .withDetail("remainingQuantity", remaining);
        }
    }

    private boolean isPurchaseFullyReturned(RawMaterialPurchase purchase) {
        if (purchase == null || purchase.getLines() == null || purchase.getLines().isEmpty()) {
            return false;
        }
        for (RawMaterialPurchaseLine line : purchase.getLines()) {
            BigDecimal lineQty = quantityValue(line.getQuantity());
            if (lineQty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal returnedQty = quantityValue(line.getReturnedQuantity());
            BigDecimal remaining = lineQty.subtract(returnedQty);
            if (remaining.compareTo(QUANTITY_TOLERANCE) > 0) {
                return false;
            }
        }
        return true;
    }

    private BigDecimal quantityValue(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private JournalEntryDto postPurchaseEntry(RawMaterialPurchaseRequest request,
                                              Supplier supplier,
                                              Map<Long, BigDecimal> inventoryDebits,
                                              BigDecimal taxAmount,
                                              BigDecimal totalAmount,
                                              String referenceNumber) {
        if (inventoryDebits.isEmpty() || totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        Company company = companyContextService.requireCurrentCompany();
        String memo = purchaseMemo(request.memo(), request.invoiceNumber(), null);
        LocalDate entryDate = request.invoiceDate() != null ? request.invoiceDate() : companyClock.today(company);

        Map<Long, BigDecimal> taxLines = null;
        if (taxAmount != null && taxAmount.compareTo(BigDecimal.ZERO) > 0) {
            taxLines = new HashMap<>();
            taxLines.put(null, taxAmount);
        }

        // Delegate to AccountingFacade for purchase journal posting
        return accountingFacade.postPurchaseJournal(
                supplier.getId(),
                request.invoiceNumber(),
                entryDate,
                memo,
                inventoryDebits,
                taxLines,
                totalAmount,
                referenceNumber
        );
    }

    private RawMaterialPurchaseResponse toResponse(RawMaterialPurchase purchase) {
        JournalEntry journalEntry = purchase.getJournalEntry();
        Supplier supplier = purchase.getSupplier();
        PurchaseOrder purchaseOrder = purchase.getPurchaseOrder();
        GoodsReceipt goodsReceipt = purchase.getGoodsReceipt();
        List<RawMaterialPurchaseLineResponse> lines = purchase.getLines().stream()
                .map(this::toLineResponse)
                .toList();
        return new RawMaterialPurchaseResponse(
                purchase.getId(),
                purchase.getPublicId(),
                purchase.getInvoiceNumber(),
                purchase.getInvoiceDate(),
                purchase.getTotalAmount(),
                purchase.getTaxAmount(),
                purchase.getOutstandingAmount(),
                purchase.getStatus(),
                purchase.getMemo(),
                supplier != null ? supplier.getId() : null,
                supplier != null ? supplier.getCode() : null,
                supplier != null ? supplier.getName() : null,
                purchaseOrder != null ? purchaseOrder.getId() : null,
                purchaseOrder != null ? purchaseOrder.getOrderNumber() : null,
                goodsReceipt != null ? goodsReceipt.getId() : null,
                goodsReceipt != null ? goodsReceipt.getReceiptNumber() : null,
                journalEntry != null ? journalEntry.getId() : null,
                purchase.getCreatedAt(),
                lines
        );
    }

    private RawMaterialPurchaseLineResponse toLineResponse(RawMaterialPurchaseLine line) {
        RawMaterial material = line.getRawMaterial();
        return new RawMaterialPurchaseLineResponse(
                material != null ? material.getId() : null,
                material != null ? material.getName() : null,
                line.getRawMaterialBatch() != null ? line.getRawMaterialBatch().getId() : null,
                line.getBatchCode(),
                line.getQuantity(),
                line.getUnit(),
                line.getCostPerUnit(),
                line.getLineTotal(),
                line.getTaxRate(),
                line.getTaxAmount(),
                line.getNotes()
        );
    }

    private RawMaterial requireMaterial(Company company, Long rawMaterialId) {
        return rawMaterialRepository.lockByCompanyAndId(company, rawMaterialId)
                .orElseThrow(() -> new IllegalArgumentException("Raw material not found"));
    }

    private BigDecimal positive(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Value for " + field + " must be greater than zero");
        }
        return value;
    }

    private PurchaseOrderResponse toPurchaseOrderResponse(PurchaseOrder order) {
        Supplier supplier = order.getSupplier();
        List<PurchaseOrderLineResponse> lines = order.getLines().stream()
                .map(this::toPurchaseOrderLineResponse)
                .toList();
        BigDecimal totalAmount = lines.stream()
                .map(PurchaseOrderLineResponse::lineTotal)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PurchaseOrderResponse(
                order.getId(),
                order.getPublicId(),
                order.getOrderNumber(),
                order.getOrderDate(),
                totalAmount,
                order.getStatus(),
                order.getMemo(),
                supplier != null ? supplier.getId() : null,
                supplier != null ? supplier.getCode() : null,
                supplier != null ? supplier.getName() : null,
                order.getCreatedAt(),
                lines
        );
    }

    private PurchaseOrderLineResponse toPurchaseOrderLineResponse(PurchaseOrderLine line) {
        RawMaterial material = line.getRawMaterial();
        return new PurchaseOrderLineResponse(
                material != null ? material.getId() : null,
                material != null ? material.getName() : null,
                line.getQuantity(),
                line.getUnit(),
                line.getCostPerUnit(),
                line.getLineTotal(),
                line.getNotes()
        );
    }

    private GoodsReceiptResponse toGoodsReceiptResponse(GoodsReceipt receipt) {
        Supplier supplier = receipt.getSupplier();
        PurchaseOrder purchaseOrder = receipt.getPurchaseOrder();
        List<GoodsReceiptLineResponse> lines = receipt.getLines().stream()
                .map(this::toGoodsReceiptLineResponse)
                .toList();
        BigDecimal totalAmount = lines.stream()
                .map(GoodsReceiptLineResponse::lineTotal)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new GoodsReceiptResponse(
                receipt.getId(),
                receipt.getPublicId(),
                receipt.getReceiptNumber(),
                receipt.getReceiptDate(),
                totalAmount,
                receipt.getStatus(),
                receipt.getMemo(),
                supplier != null ? supplier.getId() : null,
                supplier != null ? supplier.getCode() : null,
                supplier != null ? supplier.getName() : null,
                purchaseOrder != null ? purchaseOrder.getId() : null,
                purchaseOrder != null ? purchaseOrder.getOrderNumber() : null,
                receipt.getCreatedAt(),
                lines
        );
    }

    private GoodsReceiptLineResponse toGoodsReceiptLineResponse(GoodsReceiptLine line) {
        RawMaterial material = line.getRawMaterial();
        return new GoodsReceiptLineResponse(
                material != null ? material.getId() : null,
                material != null ? material.getName() : null,
                line.getBatchCode(),
                line.getQuantity(),
                line.getUnit(),
                line.getCostPerUnit(),
                line.getLineTotal(),
                line.getNotes()
        );
    }

    private BigDecimal nonNegative(BigDecimal value, String field) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Value for " + field + " must be zero or greater");
        }
        return value;
    }

    private PurchaseTaxMode resolvePurchaseTaxMode(List<RawMaterialPurchaseLineRequest> lineRequests,
                                                   Map<Long, RawMaterial> lockedMaterials) {
        PurchaseTaxMode resolved = null;
        for (RawMaterialPurchaseLineRequest lineRequest : lineRequests) {
            RawMaterial rawMaterial = lockedMaterials.get(lineRequest.rawMaterialId());
            if (rawMaterial == null) {
                continue;
            }
            PurchaseTaxMode lineMode = rawMaterial.isGstApplicable()
                    ? PurchaseTaxMode.GST
                    : PurchaseTaxMode.NON_GST;
            if (resolved == null) {
                resolved = lineMode;
                continue;
            }
            if (resolved != lineMode) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Purchase invoice cannot mix GST and non-GST materials")
                        .withDetail("rawMaterialId", rawMaterial.getId())
                        .withDetail("expectedTaxMode", taxModeLabel(resolved))
                        .withDetail("lineTaxMode", taxModeLabel(lineMode));
            }
        }
        return resolved != null ? resolved : PurchaseTaxMode.GST;
    }

    private BigDecimal resolveLineTaxRateForMode(RawMaterialPurchaseLineRequest lineRequest,
                                                 RawMaterial rawMaterial,
                                                 Company company,
                                                 PurchaseTaxMode purchaseTaxMode) {
        if (purchaseTaxMode == PurchaseTaxMode.NON_GST) {
            enforceNonGstLineContract(lineRequest, rawMaterial);
            return BigDecimal.ZERO;
        }
        return resolveLineTaxRate(lineRequest, rawMaterial, company);
    }

    private void enforcePurchaseTaxContract(PurchaseTaxMode purchaseTaxMode,
                                            BigDecimal providedTaxAmount,
                                            boolean hasTaxableLines) {
        if (providedTaxAmount == null) {
            return;
        }
        if (purchaseTaxMode == PurchaseTaxMode.NON_GST
                && providedTaxAmount.compareTo(BigDecimal.ZERO) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Non-GST purchase invoice cannot carry GST tax amount")
                    .withDetail("taxMode", taxModeLabel(purchaseTaxMode))
                    .withDetail("taxAmount", providedTaxAmount);
        }
        if (purchaseTaxMode == PurchaseTaxMode.GST
                && providedTaxAmount.compareTo(BigDecimal.ZERO) == 0
                && hasTaxableLines) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "GST purchase invoice with taxable lines requires non-zero taxAmount or tax auto-computation")
                    .withDetail("taxMode", taxModeLabel(purchaseTaxMode))
                    .withDetail("taxAmount", providedTaxAmount);
        }
    }

    private void enforceNonGstLineContract(RawMaterialPurchaseLineRequest lineRequest,
                                           RawMaterial rawMaterial) {
        BigDecimal requestedTaxRate = lineRequest != null ? lineRequest.taxRate() : null;
        if (requestedTaxRate != null && normalizePercent(requestedTaxRate).compareTo(BigDecimal.ZERO) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Non-GST purchase line cannot declare a positive GST rate")
                    .withDetail("rawMaterialId", rawMaterial != null ? rawMaterial.getId() : null)
                    .withDetail("taxRate", requestedTaxRate);
        }
        if (lineRequest != null && Boolean.TRUE.equals(lineRequest.taxInclusive())) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Non-GST purchase line cannot be tax-inclusive")
                    .withDetail("rawMaterialId", rawMaterial != null ? rawMaterial.getId() : null);
        }
        BigDecimal materialTaxRate = rawMaterial != null ? normalizePercent(rawMaterial.getGstRate()) : BigDecimal.ZERO;
        if (materialTaxRate.compareTo(BigDecimal.ZERO) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Non-GST raw material cannot carry a positive GST rate")
                    .withDetail("rawMaterialId", rawMaterial.getId())
                    .withDetail("materialGstRate", materialTaxRate);
        }
    }

    private String taxModeLabel(PurchaseTaxMode purchaseTaxMode) {
        return purchaseTaxMode == PurchaseTaxMode.NON_GST ? "NON_GST" : "GST";
    }

    private BigDecimal resolveLineTaxRate(RawMaterialPurchaseLineRequest lineRequest,
                                          RawMaterial rawMaterial,
                                          Company company) {
        if (lineRequest != null && lineRequest.taxRate() != null) {
            return normalizePercent(lineRequest.taxRate());
        }
        if (rawMaterial != null && rawMaterial.getGstRate() != null) {
            return normalizePercent(rawMaterial.getGstRate());
        }
        if (company != null && company.getDefaultGstRate() != null) {
            return normalizePercent(company.getDefaultGstRate());
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal computeReturnTax(RawMaterialPurchase purchase,
                                        RawMaterial material,
                                        BigDecimal returnQuantity) {
        if (purchase == null || material == null || returnQuantity == null) {
            return currency(BigDecimal.ZERO);
        }
        BigDecimal purchaseTax = MoneyUtils.zeroIfNull(purchase.getTaxAmount());
        if (purchaseTax.compareTo(BigDecimal.ZERO) <= 0) {
            return currency(BigDecimal.ZERO);
        }
        BigDecimal materialLineTotal = purchase.getLines().stream()
                .filter(line -> line.getRawMaterial() != null
                        && line.getRawMaterial().getId().equals(material.getId()))
                .map(RawMaterialPurchaseLine::getLineTotal)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal materialLineQty = purchase.getLines().stream()
                .filter(line -> line.getRawMaterial() != null
                        && line.getRawMaterial().getId().equals(material.getId()))
                .map(RawMaterialPurchaseLine::getQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (materialLineTotal.compareTo(BigDecimal.ZERO) <= 0 || materialLineQty.compareTo(BigDecimal.ZERO) <= 0) {
            return currency(BigDecimal.ZERO);
        }
        boolean hasCompleteLineTaxData = purchase.getLines().stream()
                .map(RawMaterialPurchaseLine::getTaxAmount)
                .allMatch(Objects::nonNull);
        if (hasCompleteLineTaxData) {
            BigDecimal materialLineTax = purchase.getLines().stream()
                    .filter(line -> line.getRawMaterial() != null
                            && line.getRawMaterial().getId().equals(material.getId()))
                    .map(RawMaterialPurchaseLine::getTaxAmount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (materialLineTax.compareTo(BigDecimal.ZERO) <= 0) {
                return currency(BigDecimal.ZERO);
            }
            BigDecimal taxPerUnit = materialLineTax.divide(materialLineQty, 6, RoundingMode.HALF_UP);
            return currency(taxPerUnit.multiply(returnQuantity));
        }

        BigDecimal inventoryTotal = purchase.getLines().stream()
                .map(RawMaterialPurchaseLine::getLineTotal)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (inventoryTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return currency(BigDecimal.ZERO);
        }
        BigDecimal allocationRatio = materialLineTotal.divide(inventoryTotal, 6, RoundingMode.HALF_UP);
        BigDecimal allocatedTax = purchaseTax.multiply(allocationRatio);
        BigDecimal taxPerUnit = allocatedTax.divide(materialLineQty, 6, RoundingMode.HALF_UP);
        return currency(taxPerUnit.multiply(returnQuantity));
    }

    private BigDecimal normalizePercent(BigDecimal rate) {
        if (rate == null) {
            return BigDecimal.ZERO;
        }
        if (rate.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("GST rate must be zero or positive");
        }
        BigDecimal sanitized = rate.setScale(2, RoundingMode.HALF_UP);
        if (sanitized.compareTo(MAX_GST_RATE) > 0) {
            throw new IllegalArgumentException("Unsupported GST rate " + sanitized + "%. Max allowed is " + MAX_GST_RATE);
        }
        return sanitized.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal currency(BigDecimal value) {
        return MoneyUtils.roundCurrency(value);
    }

    private void updatePurchaseStatus(RawMaterialPurchase purchase) {
        if (purchase == null) {
            return;
        }
        String status = purchase.getStatus();
        if (status != null && ("VOID".equalsIgnoreCase(status) || "REVERSED".equalsIgnoreCase(status))) {
            return;
        }
        BigDecimal total = MoneyUtils.zeroIfNull(purchase.getTotalAmount());
        BigDecimal outstanding = MoneyUtils.zeroIfNull(purchase.getOutstandingAmount());
        if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
            purchase.setStatus("PAID");
        } else if (total.compareTo(BigDecimal.ZERO) > 0 && outstanding.compareTo(total) < 0) {
            purchase.setStatus("PARTIAL");
        } else {
            purchase.setStatus("POSTED");
        }
    }

    private String normalizeIdempotencyKey(String raw) {
        return StringUtils.hasText(raw) ? raw.trim() : null;
    }

    private void assertIdempotencyMatch(GoodsReceipt receipt,
                                        String expectedSignature,
                                        String idempotencyKey) {
        String storedSignature = receipt.getIdempotencyHash();
        if (StringUtils.hasText(storedSignature)) {
            if (!storedSignature.equals(expectedSignature)) {
                throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                        "Idempotency key already used with different payload")
                        .withDetail("idempotencyKey", idempotencyKey)
                        .withDetail("receiptNumber", receipt.getReceiptNumber());
            }
            return;
        }
        receipt.setIdempotencyHash(expectedSignature);
        goodsReceiptRepository.save(receipt);
    }

    private String buildGoodsReceiptSignature(GoodsReceiptRequest request,
                                              List<GoodsReceiptLineRequest> sortedLines) {
        StringBuilder signature = new StringBuilder();
        signature.append(request.purchaseOrderId() != null ? request.purchaseOrderId() : "")
                .append('|').append(normalizeToken(request.receiptNumber()))
                .append('|').append(request.receiptDate() != null ? request.receiptDate() : "")
                .append('|').append(normalizeToken(request.memo()));
        for (GoodsReceiptLineRequest line : sortedLines) {
            signature.append('|').append(line.rawMaterialId() != null ? line.rawMaterialId() : "")
                    .append(':').append(normalizeToken(line.batchCode()))
                    .append(':').append(normalizeAmount(line.quantity()))
                    .append(':').append(normalizeToken(line.unit()))
                    .append(':').append(normalizeAmount(line.costPerUnit()))
                    .append(':').append(normalizeToken(line.notes()));
        }
        return DigestUtils.sha256Hex(signature.toString());
    }

    private String normalizeToken(String value) {
        return value != null ? value.trim() : "";
    }

    private String normalizeAmount(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private boolean isDataIntegrityViolation(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof DataIntegrityViolationException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private String invoiceReference(String invoiceNumber, int lineIndex) {
        String normalized = invoiceNumber == null ? "" : invoiceNumber.replaceAll("\\s+", "-");
        return normalized + "-" + lineIndex;
    }

    private String purchaseMemo(String memo, String invoiceNumber, String batchCode) {
        String base = StringUtils.hasText(memo) ? memo.trim() : "Raw material purchase " + invoiceNumber;
        if (StringUtils.hasText(batchCode)) {
            return base + " (" + batchCode + ")";
        }
        return base;
    }

    private String returnMemo(RawMaterial material, Supplier supplier, String reason) {
        String prefix = StringUtils.hasText(reason) ? reason.trim() : "Purchase return";
        return prefix + " - " + material.getName() + " to " + supplier.getName();
    }

    private String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record PurchaseLineCalc(
            RawMaterial rawMaterial,
            BigDecimal quantity,
            String unit,
            String batchCode,
            BigDecimal netUnitCost,
            BigDecimal lineNet,
            BigDecimal lineTax,
            BigDecimal taxRate,
            String notes
    ) {
    }

    private enum PurchaseTaxMode {
        GST,
        NON_GST
    }
}
