package com.bigbrightpaints.erp.modules.purchasing.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyReservationService;
import com.bigbrightpaints.erp.core.idempotency.IdempotencySignatureBuilder;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.service.RawMaterialService;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceipt;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptStatus;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrder;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderStatus;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class GoodsReceiptService {

    private final CompanyContextService companyContextService;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;
    private final com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository rawMaterialRepository;
    private final RawMaterialService rawMaterialService;
    private final CompanyEntityLookup companyEntityLookup;
    private final AccountingPeriodService accountingPeriodService;
    private final PurchaseResponseMapper responseMapper;
    private final PurchaseOrderService purchaseOrderService;
    private final IdempotencyReservationService idempotencyReservationService = new IdempotencyReservationService();
    private final TransactionTemplate transactionTemplate;

    public GoodsReceiptService(CompanyContextService companyContextService,
                               PurchaseOrderRepository purchaseOrderRepository,
                               GoodsReceiptRepository goodsReceiptRepository,
                               com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository rawMaterialRepository,
                               RawMaterialService rawMaterialService,
                               CompanyEntityLookup companyEntityLookup,
                               AccountingPeriodService accountingPeriodService,
                               PurchaseResponseMapper responseMapper,
                               PurchaseOrderService purchaseOrderService,
                               PlatformTransactionManager transactionManager) {
        this.companyContextService = companyContextService;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.goodsReceiptRepository = goodsReceiptRepository;
        this.rawMaterialRepository = rawMaterialRepository;
        this.rawMaterialService = rawMaterialService;
        this.companyEntityLookup = companyEntityLookup;
        this.accountingPeriodService = accountingPeriodService;
        this.responseMapper = responseMapper;
        this.purchaseOrderService = purchaseOrderService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
        return responseMapper.toGoodsReceiptResponses(receipts);
    }

    public GoodsReceiptResponse getGoodsReceipt(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        GoodsReceipt receipt = goodsReceiptRepository.findByCompanyAndId(company, id)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Goods receipt not found"));
        return responseMapper.toGoodsReceiptResponse(receipt);
    }

    public GoodsReceiptResponse createGoodsReceipt(GoodsReceiptRequest request) {
        if (request == null || request.lines() == null || request.lines().isEmpty()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Goods receipt lines are required");
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
            return responseMapper.toGoodsReceiptResponse(existing);
        }

        java.time.LocalDate receiptDate = request.receiptDate();
        if (receiptDate == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Receipt date is required");
        }
        try {
            return transactionTemplate.execute(status -> {
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
            return responseMapper.toGoodsReceiptResponse(concurrent);
        }
    }

    private GoodsReceiptResponse createGoodsReceiptInternal(GoodsReceiptRequest request,
                                                            Company company,
                                                            String idempotencyKey,
                                                            String requestSignature) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.lockByCompanyAndId(company, request.purchaseOrderId())
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Purchase order not found"));
        Supplier supplier = purchaseOrder.getSupplier();

        String receiptNumber = request.receiptNumber().trim();
        goodsReceiptRepository.lockByCompanyAndReceiptNumberIgnoreCase(company, receiptNumber)
                .ifPresent(existing -> {
                    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Receipt number already used for this company");
                });
        PurchaseOrderStatus purchaseOrderStatus = purchaseOrder.getStatusEnum();
        if (purchaseOrderStatus != PurchaseOrderStatus.APPROVED
                && purchaseOrderStatus != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
            throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                    "Purchase order is not receivable")
                    .withDetail("purchaseOrderId", purchaseOrder.getId())
                    .withDetail("purchaseOrderStatus", purchaseOrder.getStatus());
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
            receipt.setStatus(GoodsReceiptStatus.PARTIAL);
            purchaseOrderService.transitionStatus(
                    purchaseOrder,
                    PurchaseOrderStatus.PARTIALLY_RECEIVED,
                    "GOODS_RECEIPT_PARTIAL",
                    "Goods receipt " + receiptNumber + " partially received"
            );
        } else {
            receipt.setStatus(GoodsReceiptStatus.RECEIVED);
            purchaseOrderService.transitionStatus(
                    purchaseOrder,
                    PurchaseOrderStatus.FULLY_RECEIVED,
                    "GOODS_RECEIPT_COMPLETED",
                    "Goods receipt " + receiptNumber + " fully received"
            );
        }

        Map<Long, GoodsReceiptLineRequest> requestLinesByMaterial = new HashMap<>();
        for (GoodsReceiptLineRequest lineRequest : request.lines()) {
            if (lineRequest.rawMaterialId() != null) {
                requestLinesByMaterial.put(lineRequest.rawMaterialId(), lineRequest);
            }
        }

        for (GoodsReceiptLine line : receipt.getLines()) {
            RawMaterial rawMaterial = line.getRawMaterial();
            GoodsReceiptLineRequest lineRequest = requestLinesByMaterial.get(rawMaterial.getId());
            RawMaterialBatchRequest batchRequest = new RawMaterialBatchRequest(
                    line.getBatchCode(),
                    line.getQuantity(),
                    line.getUnit(),
                    line.getCostPerUnit(),
                    supplier.getId(),
                    lineRequest != null ? lineRequest.manufacturingDate() : null,
                    lineRequest != null ? lineRequest.expiryDate() : null,
                    line.getNotes()
            );
            RawMaterialService.ReceiptContext context = new RawMaterialService.ReceiptContext(
                    InventoryReference.GOODS_RECEIPT,
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

        return responseMapper.toGoodsReceiptResponse(savedReceipt);
    }

    private RawMaterial requireMaterial(Company company, Long rawMaterialId) {
        return rawMaterialRepository.lockByCompanyAndId(company, rawMaterialId)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Raw material not found"));
    }

    private BigDecimal positive(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Value for " + field + " must be greater than zero");
        }
        return value;
    }

    private BigDecimal currency(BigDecimal value) {
        return MoneyUtils.roundCurrency(value);
    }

    private String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeIdempotencyKey(String raw) {
        return idempotencyReservationService.normalizeKey(raw);
    }

    private void assertIdempotencyMatch(GoodsReceipt receipt,
                                        String expectedSignature,
                                        String idempotencyKey) {
        idempotencyReservationService.assertAndRepairSignature(
                receipt,
                idempotencyKey,
                expectedSignature,
                GoodsReceipt::getIdempotencyHash,
                GoodsReceipt::setIdempotencyHash,
                goodsReceiptRepository::save,
                () -> new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                        "Idempotency key already used with different payload")
                        .withDetail("idempotencyKey", idempotencyKey)
                        .withDetail("receiptNumber", receipt.getReceiptNumber())
        );
    }

    private String buildGoodsReceiptSignature(GoodsReceiptRequest request,
                                              List<GoodsReceiptLineRequest> sortedLines) {
        IdempotencySignatureBuilder signature = IdempotencySignatureBuilder.create()
                .add(request.purchaseOrderId() != null ? request.purchaseOrderId() : "")
                .addToken(request.receiptNumber())
                .add(request.receiptDate() != null ? request.receiptDate() : "")
                .addToken(request.memo());
        for (GoodsReceiptLineRequest line : sortedLines) {
            signature.add(
                    (line.rawMaterialId() != null ? line.rawMaterialId() : "")
                            + ":" + IdempotencyUtils.normalizeToken(line.batchCode())
                            + ":" + IdempotencyUtils.normalizeAmount(line.quantity())
                            + ":" + IdempotencyUtils.normalizeToken(line.unit())
                            + ":" + IdempotencyUtils.normalizeAmount(line.costPerUnit())
                            + ":" + (line.manufacturingDate() != null ? line.manufacturingDate() : "")
                            + ":" + (line.expiryDate() != null ? line.expiryDate() : "")
                            + ":" + IdempotencyUtils.normalizeToken(line.notes())
            );
        }
        return signature.buildHash();
    }

    private boolean isDataIntegrityViolation(Throwable error) {
        return idempotencyReservationService.isDataIntegrityViolation(error);
    }
}
