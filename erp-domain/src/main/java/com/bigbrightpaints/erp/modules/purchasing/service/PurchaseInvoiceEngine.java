package com.bigbrightpaints.erp.modules.purchasing.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyReservationService;
import com.bigbrightpaints.erp.core.idempotency.IdempotencySignatureBuilder;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyScopedAccountingLookupService;
import com.bigbrightpaints.erp.modules.accounting.service.GstService;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.service.CompanyScopedInventoryLookupService;
import com.bigbrightpaints.erp.modules.inventory.service.RawMaterialService;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceipt;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptStatus;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrder;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderStatus;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierPaymentTerms;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseResponse;

import jakarta.transaction.Transactional;

@Component
public class PurchaseInvoiceEngine {

  private static final BigDecimal UNIT_COST_TOLERANCE = new BigDecimal("0.01");

  private final CompanyContextService companyContextService;
  private final RawMaterialPurchaseRepository purchaseRepository;
  private final PurchaseOrderRepository purchaseOrderRepository;
  private final GoodsReceiptRepository goodsReceiptRepository;
  private final RawMaterialRepository rawMaterialRepository;
  private final RawMaterialBatchRepository rawMaterialBatchRepository;
  private final RawMaterialService rawMaterialService;
  private final RawMaterialMovementRepository movementRepository;
  private final AccountingFacade accountingFacade;
  private final CompanyScopedPurchasingLookupService purchasingLookupService;
  private final CompanyScopedInventoryLookupService inventoryLookupService;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final ReferenceNumberService referenceNumberService;
  private final CompanyClock companyClock;
  private final GstService gstService;
  private final PurchaseResponseMapper responseMapper;
  private final PurchaseTaxPolicy purchaseTaxPolicy;
  private final IdempotencyReservationService idempotencyReservationService =
      new IdempotencyReservationService();
  private PurchaseOrderService purchaseOrderService;

  @Autowired
  public PurchaseInvoiceEngine(
      CompanyContextService companyContextService,
      RawMaterialPurchaseRepository purchaseRepository,
      PurchaseOrderRepository purchaseOrderRepository,
      GoodsReceiptRepository goodsReceiptRepository,
      RawMaterialRepository rawMaterialRepository,
      RawMaterialBatchRepository rawMaterialBatchRepository,
      RawMaterialService rawMaterialService,
      RawMaterialMovementRepository movementRepository,
      AccountingFacade accountingFacade,
      CompanyScopedPurchasingLookupService purchasingLookupService,
      CompanyScopedInventoryLookupService inventoryLookupService,
      CompanyScopedAccountingLookupService accountingLookupService,
      ReferenceNumberService referenceNumberService,
      CompanyClock companyClock,
      GstService gstService,
      PurchaseResponseMapper responseMapper,
      PurchaseTaxPolicy purchaseTaxPolicy) {
    this.companyContextService = companyContextService;
    this.purchaseRepository = purchaseRepository;
    this.purchaseOrderRepository = purchaseOrderRepository;
    this.goodsReceiptRepository = goodsReceiptRepository;
    this.rawMaterialRepository = rawMaterialRepository;
    this.rawMaterialBatchRepository = rawMaterialBatchRepository;
    this.rawMaterialService = rawMaterialService;
    this.movementRepository = movementRepository;
    this.accountingFacade = accountingFacade;
    this.purchasingLookupService = purchasingLookupService;
    this.inventoryLookupService = inventoryLookupService;
    this.accountingLookupService = accountingLookupService;
    this.referenceNumberService = referenceNumberService;
    this.companyClock = companyClock;
    this.gstService = gstService;
    this.responseMapper = responseMapper;
    this.purchaseTaxPolicy = purchaseTaxPolicy;
  }

  void setPurchaseOrderService(PurchaseOrderService purchaseOrderService) {
    this.purchaseOrderService = purchaseOrderService;
  }

  public List<RawMaterialPurchaseResponse> listPurchases() {
    return listPurchases(null);
  }

  public List<RawMaterialPurchaseResponse> listPurchases(Long supplierId) {
    Company company = companyContextService.requireCurrentCompany();
    Supplier supplier =
        supplierId != null ? purchasingLookupService.requireSupplier(company, supplierId) : null;
    List<RawMaterialPurchase> purchases =
        supplier == null
            ? purchaseRepository.findByCompanyWithLinesOrderByInvoiceDateDesc(company)
            : purchaseRepository.findByCompanyAndSupplierWithLinesOrderByInvoiceDateDesc(
                company, supplier);
    return responseMapper.toPurchaseResponses(purchases);
  }

  public RawMaterialPurchaseResponse getPurchase(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    RawMaterialPurchase purchase = purchasingLookupService.requireRawMaterialPurchase(company, id);
    return responseMapper.toPurchaseResponse(purchase);
  }

  @Transactional
  public RawMaterialPurchaseResponse createPurchase(RawMaterialPurchaseRequest request) {
    return createPurchase(request, null);
  }

  @Transactional
  public RawMaterialPurchaseResponse createPurchase(
      RawMaterialPurchaseRequest request, String idempotencyKey) {
    Company company = companyContextService.requireCurrentCompany();
    String canonicalIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);
    List<RawMaterialPurchaseLineRequest> sortedLines =
        request.lines().stream()
            .sorted(Comparator.comparing(RawMaterialPurchaseLineRequest::rawMaterialId))
            .toList();
    String requestSignature =
        StringUtils.hasText(canonicalIdempotencyKey)
            ? buildPurchaseInvoiceSignature(request, sortedLines)
            : null;

    if (StringUtils.hasText(canonicalIdempotencyKey)) {
      RawMaterialPurchase existing =
          purchaseRepository
              .findWithLinesByCompanyAndIdempotencyKey(company, canonicalIdempotencyKey)
              .orElse(null);
      if (existing != null) {
        assertIdempotencyMatch(existing, requestSignature, canonicalIdempotencyKey);
        return responseMapper.toPurchaseResponse(existing);
      }
    }

    try {
      return createPurchaseInternal(
          request, company, sortedLines, canonicalIdempotencyKey, requestSignature);
    } catch (RuntimeException ex) {
      if (!StringUtils.hasText(canonicalIdempotencyKey) || !isDataIntegrityViolation(ex)) {
        throw ex;
      }
      RawMaterialPurchase concurrent =
          purchaseRepository
              .findWithLinesByCompanyAndIdempotencyKey(company, canonicalIdempotencyKey)
              .orElseThrow(() -> ex);
      assertIdempotencyMatch(concurrent, requestSignature, canonicalIdempotencyKey);
      return responseMapper.toPurchaseResponse(concurrent);
    }
  }

  private RawMaterialPurchaseResponse createPurchaseInternal(
      RawMaterialPurchaseRequest request,
      Company company,
      List<RawMaterialPurchaseLineRequest> sortedLines,
      String idempotencyKey,
      String requestSignature) {
    Supplier supplier = purchasingLookupService.requireSupplier(company, request.supplierId());
    supplier.requireTransactionalUsage("post purchase invoices");
    if (supplier.getPayableAccount() == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Supplier " + supplier.getName() + " is missing a payable account");
    }
    String invoiceNumber = request.invoiceNumber().trim();

    purchaseRepository
        .lockByCompanyAndInvoiceNumberIgnoreCase(company, invoiceNumber)
        .ifPresent(
            existing -> {
              throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                  "Invoice number already used for this company");
            });

    GoodsReceipt goodsReceipt =
        goodsReceiptRepository
            .lockByCompanyAndId(company, request.goodsReceiptId())
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Goods receipt not found"));
    if (!supplier.getId().equals(goodsReceipt.getSupplier().getId())) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE, "Goods receipt does not belong to the supplier");
    }
    if (goodsReceipt.getStatusEnum() == GoodsReceiptStatus.INVOICED) {
      throw new ApplicationException(
          ErrorCode.BUSINESS_CONSTRAINT_VIOLATION, "Goods receipt is already invoiced");
    }
    PurchaseOrder purchaseOrder = goodsReceipt.getPurchaseOrder();
    if (request.purchaseOrderId() != null
        && (purchaseOrder == null || !purchaseOrder.getId().equals(request.purchaseOrderId()))) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE, "Purchase order does not match goods receipt");
    }
    if (purchaseOrder == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE, "Goods receipt is missing a purchase order");
    }
    purchaseRepository
        .findByCompanyAndGoodsReceipt(company, goodsReceipt)
        .ifPresent(
            existing -> {
              throw new ApplicationException(
                      ErrorCode.CONCURRENCY_CONFLICT,
                      "Goods receipt already linked to a purchase invoice")
                  .withDetail("purchaseId", existing.getId());
            });

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
        throw new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT,
                "Goods receipt has duplicate raw material lines")
            .withDetail("rawMaterialId", materialId);
      }
      receiptQuantities.put(materialId, line.getQuantity());
      receiptUnitCosts.put(materialId, line.getCostPerUnit());
      receiptUnits.put(materialId, line.getUnit());
      receiptLinesByMaterial.put(materialId, line);
    }
    requireGoodsReceiptMovementsReadyForInvoicing(company, goodsReceipt, receiptLinesByMaterial);

    boolean taxProvided = request.taxAmount() != null;
    Set<Long> invoiceMaterialIds = new HashSet<>();

    Map<Long, RawMaterial> lockedMaterials = new HashMap<>();
    for (RawMaterialPurchaseLineRequest lineRequest : sortedLines) {
      RawMaterial rawMaterial = requireMaterial(company, lineRequest.rawMaterialId());
      lockedMaterials.put(rawMaterial.getId(), rawMaterial);
    }

    PurchaseTaxPolicy.PurchaseTaxMode purchaseTaxMode =
        purchaseTaxPolicy.resolvePurchaseTaxMode(sortedLines, lockedMaterials);

    BigDecimal inventoryTotal = BigDecimal.ZERO;
    BigDecimal taxTotal = BigDecimal.ZERO;
    BigDecimal totalCgst = BigDecimal.ZERO;
    BigDecimal totalSgst = BigDecimal.ZERO;
    BigDecimal totalIgst = BigDecimal.ZERO;
    String companyStateCode = resolveCompanyStateCode(company);
    String supplierStateCode = resolveSupplierStateCode(supplier);
    if (!StringUtils.hasText(supplier.getStateCode()) && StringUtils.hasText(supplierStateCode)) {
      supplier.setStateCode(supplierStateCode);
    }
    Map<Long, BigDecimal> inventoryDebits = new HashMap<>();
    List<PurchaseLineCalc> computedLines = new ArrayList<>();
    boolean hasTaxableLines = false;

    for (RawMaterialPurchaseLineRequest lineRequest : request.lines()) {
      RawMaterial rawMaterial = lockedMaterials.get(lineRequest.rawMaterialId());
      if (rawMaterial == null) {
        throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
            "Raw material not found");
      }
      BigDecimal quantity = positive(lineRequest.quantity(), "quantity");
      BigDecimal costPerUnit = positive(lineRequest.costPerUnit(), "costPerUnit");
      String unit =
          StringUtils.hasText(lineRequest.unit())
              ? lineRequest.unit().trim()
              : rawMaterial.getUnitType();
      String batchCode =
          StringUtils.hasText(lineRequest.batchCode()) ? lineRequest.batchCode().trim() : null;

      BigDecimal receiptQty = receiptQuantities.get(rawMaterial.getId());
      if (receiptQty == null) {
        throw new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT, "Purchase line is not covered by goods receipt")
            .withDetail("rawMaterialId", rawMaterial.getId());
      }
      if (!invoiceMaterialIds.add(rawMaterial.getId())) {
        throw new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT,
                "Purchase invoice has duplicate raw material lines")
            .withDetail("rawMaterialId", rawMaterial.getId());
      }
      if (quantity.compareTo(receiptQty) != 0) {
        throw new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT,
                "Purchase quantity must match goods receipt quantity")
            .withDetail("rawMaterialId", rawMaterial.getId())
            .withDetail("receiptQuantity", receiptQty)
            .withDetail("invoiceQuantity", quantity);
      }
      BigDecimal receiptCost = receiptUnitCosts.get(rawMaterial.getId());
      if (receiptCost != null
          && !MoneyUtils.withinTolerance(receiptCost, costPerUnit, UNIT_COST_TOLERANCE)) {
        throw new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT,
                "Purchase unit cost must match goods receipt cost")
            .withDetail("rawMaterialId", rawMaterial.getId())
            .withDetail("receiptCostPerUnit", receiptCost)
            .withDetail("invoiceCostPerUnit", costPerUnit);
      }
      String receiptUnit = receiptUnits.get(rawMaterial.getId());
      if (receiptUnit != null && !receiptUnit.equalsIgnoreCase(unit)) {
        throw new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT, "Purchase unit must match goods receipt unit")
            .withDetail("rawMaterialId", rawMaterial.getId())
            .withDetail("receiptUnit", receiptUnit)
            .withDetail("invoiceUnit", unit);
      }
      if (taxProvided && (lineRequest.taxRate() != null || lineRequest.taxInclusive() != null)) {
        throw new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT,
                "taxAmount cannot be combined with line-level taxRate or taxInclusive")
            .withDetail("rawMaterialId", rawMaterial.getId());
      }

      BigDecimal lineGrossRaw = MoneyUtils.safeMultiply(quantity, costPerUnit);
      BigDecimal lineGross = taxProvided ? lineGrossRaw : currency(lineGrossRaw);
      BigDecimal lineNet = lineGross;
      BigDecimal lineTax = currency(BigDecimal.ZERO);
      BigDecimal lineTaxRate = null;
      BigDecimal netUnitCost = costPerUnit;
      BigDecimal effectiveTaxRate =
          purchaseTaxPolicy.resolveLineTaxRateForMode(
              lineRequest, rawMaterial, company, purchaseTaxMode);
      if (Boolean.TRUE.equals(lineRequest.taxInclusive())
          && effectiveTaxRate.compareTo(BigDecimal.ZERO) <= 0) {
        throw new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT,
                "Tax-inclusive purchase line requires a positive GST rate")
            .withDetail("rawMaterialId", rawMaterial.getId());
      }
      if (effectiveTaxRate.compareTo(BigDecimal.ZERO) > 0) {
        hasTaxableLines = true;
      }

      if (!taxProvided) {
        lineTaxRate = effectiveTaxRate;
        boolean taxInclusive = Boolean.TRUE.equals(lineRequest.taxInclusive());
        if (effectiveTaxRate.compareTo(BigDecimal.ZERO) > 0) {
          if (taxInclusive) {
            BigDecimal divisor =
                BigDecimal.ONE.add(
                    effectiveTaxRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
            if (divisor.signum() > 0) {
              BigDecimal net = lineGross.divide(divisor, 6, RoundingMode.HALF_UP);
              lineNet = currency(net);
              lineTax = currency(lineGross.subtract(lineNet));
              netUnitCost = lineNet.divide(quantity, 6, RoundingMode.HALF_UP);
            }
          } else {
            lineNet = lineGross;
            lineTax =
                currency(
                    lineNet
                        .multiply(effectiveTaxRate)
                        .divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
          }
        }
      }

      Long inventoryAccountId = rawMaterial.getInventoryAccountId();
      if (inventoryAccountId == null) {
        throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
            "Raw material " + rawMaterial.getName() + " is missing an inventory account");
      }

      inventoryTotal = inventoryTotal.add(lineNet);
      taxTotal = taxTotal.add(lineTax);
      GstService.GstBreakdown lineBreakdown =
          splitTaxAmountSafe(lineNet, lineTax, companyStateCode, supplierStateCode);
      totalCgst = totalCgst.add(lineBreakdown.cgst());
      totalSgst = totalSgst.add(lineBreakdown.sgst());
      totalIgst = totalIgst.add(lineBreakdown.igst());
      inventoryDebits.merge(inventoryAccountId, lineNet, BigDecimal::add);
      computedLines.add(
          new PurchaseLineCalc(
              rawMaterial,
              quantity,
              unit,
              batchCode,
              netUnitCost,
              lineNet,
              lineTax,
              lineTaxRate,
              lineRequest.notes(),
              lineBreakdown.cgst(),
              lineBreakdown.sgst(),
              lineBreakdown.igst()));
    }

    if (invoiceMaterialIds.size() != receiptQuantities.size()) {
      List<Long> missingMaterialIds =
          receiptQuantities.keySet().stream()
              .filter(materialId -> !invoiceMaterialIds.contains(materialId))
              .sorted()
              .toList();
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Purchase invoice must include all goods receipt lines")
          .withDetail("missingRawMaterialIds", missingMaterialIds);
    }

    BigDecimal providedTaxAmount =
        taxProvided ? currency(nonNegative(request.taxAmount(), "taxAmount")) : null;
    purchaseTaxPolicy.enforcePurchaseTaxContract(
        purchaseTaxMode, providedTaxAmount, hasTaxableLines);
    BigDecimal taxAmount = taxProvided ? providedTaxAmount : currency(taxTotal);
    BigDecimal totalAmount = inventoryTotal.add(taxAmount);

    if (taxProvided
        && taxAmount.compareTo(BigDecimal.ZERO) > 0
        && inventoryTotal.compareTo(BigDecimal.ZERO) > 0
        && !computedLines.isEmpty()) {
      List<PurchaseLineCalc> allocatedLines = new ArrayList<>(computedLines.size());
      BigDecimal remaining = taxAmount;
      totalCgst = BigDecimal.ZERO;
      totalSgst = BigDecimal.ZERO;
      totalIgst = BigDecimal.ZERO;
      for (int i = 0; i < computedLines.size(); i++) {
        PurchaseLineCalc line = computedLines.get(i);
        BigDecimal allocatedTax =
            (i == computedLines.size() - 1)
                ? remaining
                : currency(
                    line.lineNet()
                        .multiply(taxAmount)
                        .divide(inventoryTotal, 6, RoundingMode.HALF_UP));
        remaining = remaining.subtract(allocatedTax);
        GstService.GstBreakdown lineBreakdown =
            splitTaxAmountSafe(line.lineNet(), allocatedTax, companyStateCode, supplierStateCode);
        totalCgst = totalCgst.add(lineBreakdown.cgst());
        totalSgst = totalSgst.add(lineBreakdown.sgst());
        totalIgst = totalIgst.add(lineBreakdown.igst());
        allocatedLines.add(
            new PurchaseLineCalc(
                line.rawMaterial(),
                line.quantity(),
                line.unit(),
                line.batchCode(),
                line.netUnitCost(),
                line.lineNet(),
                allocatedTax,
                null,
                line.notes(),
                lineBreakdown.cgst(),
                lineBreakdown.sgst(),
                lineBreakdown.igst()));
      }
      computedLines = allocatedLines;
    }

    JournalCreationRequest.GstBreakdown gstBreakdown =
        taxAmount.compareTo(BigDecimal.ZERO) > 0
            ? new JournalCreationRequest.GstBreakdown(
                inventoryTotal, totalCgst, totalSgst, totalIgst)
            : null;

    String referenceNumber =
        referenceNumberService.purchaseReference(company, supplier, invoiceNumber);
    JournalEntryDto entry =
        postPurchaseEntry(
            request,
            supplier,
            inventoryDebits,
            taxAmount,
            totalAmount,
            referenceNumber,
            gstBreakdown);

    com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry linkedJournal = null;
    if (entry != null) {
      linkedJournal = accountingLookupService.requireJournalEntry(company, entry.id());
    }

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setCompany(company);
    purchase.setSupplier(supplier);
    purchase.setInvoiceNumber(invoiceNumber);
    purchase.setInvoiceDate(request.invoiceDate());
    purchase.setDueDate(resolvePurchaseDueDate(request.invoiceDate(), supplier));
    purchase.setMemo(clean(request.memo()));
    purchase.setTotalAmount(totalAmount);
    purchase.setTaxAmount(taxAmount);
    purchase.setOutstandingAmount(totalAmount);
    purchase.setJournalEntry(linkedJournal);
    purchase.setPurchaseOrder(purchaseOrder);
    purchase.setGoodsReceipt(goodsReceipt);
    purchase.setIdempotencyKey(idempotencyKey);
    purchase.setIdempotencyHash(requestSignature);

    for (PurchaseLineCalc lineCalc : computedLines) {
      RawMaterial rawMaterial = lineCalc.rawMaterial();
      BigDecimal quantity = lineCalc.quantity();
      BigDecimal costPerUnit = lineCalc.netUnitCost();
      String unit = lineCalc.unit();
      BigDecimal lineTotal = lineCalc.lineNet();

      GoodsReceiptLine receiptLine = receiptLinesByMaterial.get(rawMaterial.getId());
      RawMaterialBatch batch = receiptLine.getRawMaterialBatch();

      RawMaterialPurchaseLine line = new RawMaterialPurchaseLine();
      line.setPurchase(purchase);
      line.setRawMaterial(rawMaterial);
      line.setRawMaterialBatch(batch);
      line.setBatchCode(batch.getBatchCode());
      line.setQuantity(quantity);
      line.setUnit(unit);
      line.setCostPerUnit(costPerUnit);
      line.setLineTotal(lineTotal);
      line.setTaxAmount(lineCalc.lineTax());
      line.setTaxRate(lineCalc.taxRate());
      line.setCgstAmount(lineCalc.cgstAmount());
      line.setSgstAmount(lineCalc.sgstAmount());
      line.setIgstAmount(lineCalc.igstAmount());
      line.setNotes(lineCalc.notes());
      purchase.addLine(line);
    }

    purchase = purchaseRepository.save(purchase);

    if (entry != null) {
      linkGoodsReceiptMovementsToJournal(company, goodsReceipt.getReceiptNumber(), entry.id());
    }
    goodsReceipt.setStatus(GoodsReceiptStatus.INVOICED);
    goodsReceiptRepository.save(goodsReceipt);
    if (purchaseOrder.getStatusEnum() == PurchaseOrderStatus.FULLY_RECEIVED
        || purchaseOrder.getStatusEnum() == PurchaseOrderStatus.PARTIALLY_RECEIVED) {
      boolean allInvoiced =
          goodsReceiptRepository.findByPurchaseOrder(purchaseOrder).stream()
              .allMatch(gr -> gr.getStatusEnum() == GoodsReceiptStatus.INVOICED);
      if (allInvoiced) {
        transitionPurchaseOrderStatus(
            purchaseOrder,
            PurchaseOrderStatus.INVOICED,
            "PURCHASE_ORDER_INVOICED",
            "All goods receipts have been invoiced");
        transitionPurchaseOrderStatus(
            purchaseOrder,
            PurchaseOrderStatus.CLOSED,
            "PURCHASE_ORDER_CLOSED",
            "Purchase order closed after invoice posting");
        purchaseOrderRepository.save(purchaseOrder);
      } else {
        transitionPurchaseOrderStatus(
            purchaseOrder,
            PurchaseOrderStatus.INVOICED,
            "PURCHASE_ORDER_INVOICED",
            "Goods receipt " + goodsReceipt.getReceiptNumber() + " invoiced");
        purchaseOrderRepository.save(purchaseOrder);
      }
    }
    return responseMapper.toPurchaseResponse(purchase);
  }

  private String normalizeIdempotencyKey(String raw) {
    String normalized = idempotencyReservationService.normalizeKey(raw);
    if (!StringUtils.hasText(normalized)) {
      return null;
    }
    return idempotencyReservationService.requireKey(normalized, "purchase invoices");
  }

  private String buildPurchaseInvoiceSignature(
      RawMaterialPurchaseRequest request, List<RawMaterialPurchaseLineRequest> sortedLines) {
    IdempotencySignatureBuilder signature =
        IdempotencySignatureBuilder.create()
            .add(request.supplierId() != null ? request.supplierId() : "")
            .addToken(request.invoiceNumber())
            .add(request.invoiceDate() != null ? request.invoiceDate() : "")
            .add(request.purchaseOrderId() != null ? request.purchaseOrderId() : "")
            .add(request.goodsReceiptId() != null ? request.goodsReceiptId() : "")
            .addToken(request.memo())
            .addAmount(request.taxAmount());

    for (RawMaterialPurchaseLineRequest line : sortedLines) {
      signature.add(
          (line.rawMaterialId() != null ? line.rawMaterialId() : "")
              + ":"
              + IdempotencyUtils.normalizeToken(line.batchCode())
              + ":"
              + IdempotencyUtils.normalizeAmount(line.quantity())
              + ":"
              + IdempotencyUtils.normalizeToken(line.unit())
              + ":"
              + IdempotencyUtils.normalizeAmount(line.costPerUnit())
              + ":"
              + IdempotencyUtils.normalizeAmount(line.taxRate())
              + ":"
              + (line.taxInclusive() != null ? line.taxInclusive() : "")
              + ":"
              + IdempotencyUtils.normalizeToken(line.notes()));
    }
    return signature.buildHash();
  }

  private void assertIdempotencyMatch(
      RawMaterialPurchase purchase, String expectedSignature, String idempotencyKey) {
    idempotencyReservationService.assertAndRepairSignature(
        purchase,
        idempotencyKey,
        expectedSignature,
        RawMaterialPurchase::getIdempotencyHash,
        RawMaterialPurchase::setIdempotencyHash,
        purchaseRepository::save,
        () ->
            new ApplicationException(
                    ErrorCode.CONCURRENCY_CONFLICT,
                    "Idempotency key already used with different payload")
                .withDetail("idempotencyKey", idempotencyKey)
                .withDetail("invoiceNumber", purchase.getInvoiceNumber()));
  }

  private boolean isDataIntegrityViolation(Throwable error) {
    if (error instanceof DataIntegrityViolationException) {
      return true;
    }
    return idempotencyReservationService.isDataIntegrityViolation(error);
  }

  private List<RawMaterialMovement> requireGoodsReceiptMovementsReadyForInvoicing(
      Company company,
      GoodsReceipt goodsReceipt,
      Map<Long, GoodsReceiptLine> receiptLinesByMaterial) {
    if (!StringUtils.hasText(goodsReceipt.getReceiptNumber())) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
              "Goods receipt linkage is incomplete; purchase invoice requires a persisted goods"
                  + " receipt reference")
          .withDetail("goodsReceiptId", goodsReceipt.getId());
    }
    if (receiptLinesByMaterial.isEmpty()) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
              "Goods receipt has no stock lines to anchor purchase invoice")
          .withDetail("goodsReceiptId", goodsReceipt.getId())
          .withDetail("goodsReceiptNumber", goodsReceipt.getReceiptNumber());
    }

    List<RawMaterialMovement> receiptMovements =
        findGoodsReceiptMovements(company, goodsReceipt.getReceiptNumber());
    if (receiptMovements.isEmpty()) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
              "Goods receipt "
                  + goodsReceipt.getReceiptNumber()
                  + " has no stock movement to anchor purchase invoice")
          .withDetail("goodsReceiptId", goodsReceipt.getId())
          .withDetail("goodsReceiptNumber", goodsReceipt.getReceiptNumber());
    }

    Set<Long> movementMaterialIds = new HashSet<>();
    for (RawMaterialMovement movement : receiptMovements) {
      Long existingJournalId = movement.getJournalEntryId();
      if (existingJournalId != null) {
        throw new ApplicationException(
                ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                "Goods receipt "
                    + goodsReceipt.getReceiptNumber()
                    + " already linked to journal "
                    + existingJournalId)
            .withDetail("goodsReceiptNumber", goodsReceipt.getReceiptNumber())
            .withDetail("movementId", movement.getId())
            .withDetail("existingJournalEntryId", existingJournalId);
      }
      if (movement.getRawMaterial() != null && movement.getRawMaterial().getId() != null) {
        movementMaterialIds.add(movement.getRawMaterial().getId());
      }
    }

    Set<Long> receiptMaterialIds = new HashSet<>(receiptLinesByMaterial.keySet());
    if (!movementMaterialIds.equals(receiptMaterialIds)) {
      Set<Long> missingMovementMaterialIds = new HashSet<>(receiptMaterialIds);
      missingMovementMaterialIds.removeAll(movementMaterialIds);
      Set<Long> unexpectedMovementMaterialIds = new HashSet<>(movementMaterialIds);
      unexpectedMovementMaterialIds.removeAll(receiptMaterialIds);
      throw new ApplicationException(
              ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
              "Goods receipt movement linkage drift detected; purchase invoice cannot post AP"
                  + " truth")
          .withDetail("goodsReceiptId", goodsReceipt.getId())
          .withDetail("goodsReceiptNumber", goodsReceipt.getReceiptNumber())
          .withDetail("missingMovementRawMaterialIds", missingMovementMaterialIds)
          .withDetail("unexpectedMovementRawMaterialIds", unexpectedMovementMaterialIds);
    }

    for (Map.Entry<Long, GoodsReceiptLine> entry : receiptLinesByMaterial.entrySet()) {
      if (entry.getValue().getRawMaterialBatch() == null) {
        throw new ApplicationException(
                ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                "Goods receipt line is missing batch linkage; invoice cannot create AP truth")
            .withDetail("goodsReceiptId", goodsReceipt.getId())
            .withDetail("goodsReceiptNumber", goodsReceipt.getReceiptNumber())
            .withDetail("rawMaterialId", entry.getKey());
      }
    }

    return receiptMovements;
  }

  private void linkGoodsReceiptMovementsToJournal(
      Company company, String receiptNumber, Long journalEntryId) {
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
                "Goods receipt "
                    + receiptNumber
                    + " already linked to journal "
                    + existingJournalId)
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

  private List<RawMaterialMovement> findGoodsReceiptMovements(
      Company company, String receiptNumber) {
    List<RawMaterialMovement> movements =
        movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.GOODS_RECEIPT, receiptNumber);
    return movements != null ? movements : List.of();
  }

  private GstService.GstBreakdown splitTaxAmountSafe(
      BigDecimal taxableAmount,
      BigDecimal taxAmount,
      String sourceStateCode,
      String supplierStateCode) {
    GstService.GstBreakdown lineBreakdown =
        gstService.splitTaxAmount(taxableAmount, taxAmount, sourceStateCode, supplierStateCode);
    if (lineBreakdown != null) {
      return lineBreakdown;
    }
    return fallbackTaxBreakdown(taxableAmount, taxAmount, sourceStateCode, supplierStateCode);
  }

  private GstService.GstBreakdown fallbackTaxBreakdown(
      BigDecimal taxableAmount,
      BigDecimal taxAmount,
      String sourceStateCode,
      String supplierStateCode) {
    GstService.TaxType taxType =
        gstService.resolveTaxType(sourceStateCode, supplierStateCode, false);
    if (taxType == GstService.TaxType.INTER_STATE) {
      return new GstService.GstBreakdown(
          currency(taxableAmount), BigDecimal.ZERO, BigDecimal.ZERO, currency(taxAmount), taxType);
    }
    BigDecimal roundedTax = currency(taxAmount);
    BigDecimal cgst = currency(roundedTax.divide(new BigDecimal("2"), 6, RoundingMode.HALF_UP));
    BigDecimal sgst = currency(roundedTax.subtract(cgst));
    return new GstService.GstBreakdown(
        currency(taxableAmount), cgst, sgst, BigDecimal.ZERO, taxType);
  }

  private JournalEntryDto postPurchaseEntry(
      RawMaterialPurchaseRequest request,
      Supplier supplier,
      Map<Long, BigDecimal> inventoryDebits,
      BigDecimal taxAmount,
      BigDecimal totalAmount,
      String referenceNumber,
      JournalCreationRequest.GstBreakdown gstBreakdown) {
    if (inventoryDebits.isEmpty() || totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    Company company = companyContextService.requireCurrentCompany();
    String memo = purchaseMemo(request.memo(), request.invoiceNumber(), null);
    java.time.LocalDate entryDate =
        request.invoiceDate() != null ? request.invoiceDate() : companyClock.today(company);

    Map<Long, BigDecimal> taxLines = null;
    if (taxAmount != null && taxAmount.compareTo(BigDecimal.ZERO) > 0) {
      taxLines = new HashMap<>();
      taxLines.put(null, taxAmount);
    }

    return accountingFacade.postPurchaseJournal(
        supplier.getId(),
        request.invoiceNumber(),
        entryDate,
        memo,
        inventoryDebits,
        taxLines,
        gstBreakdown,
        totalAmount,
        referenceNumber);
  }

  private RawMaterial requireMaterial(Company company, Long rawMaterialId) {
    try {
      return inventoryLookupService.lockActiveRawMaterial(company, rawMaterialId);
    } catch (IllegalArgumentException ex) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Raw material not found");
    }
  }

  private BigDecimal positive(BigDecimal value, String field) {
    if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Value for " + field + " must be greater than zero");
    }
    return value;
  }

  private BigDecimal nonNegative(BigDecimal value, String field) {
    if (value == null) {
      return BigDecimal.ZERO;
    }
    if (value.compareTo(BigDecimal.ZERO) < 0) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Value for " + field + " must be zero or greater");
    }
    return value;
  }

  private BigDecimal currency(BigDecimal value) {
    return MoneyUtils.roundCurrency(value);
  }

  private String purchaseMemo(String memo, String invoiceNumber, String batchCode) {
    String base =
        StringUtils.hasText(memo) ? memo.trim() : "Raw material purchase " + invoiceNumber;
    if (StringUtils.hasText(batchCode)) {
      return base + " (" + batchCode + ")";
    }
    return base;
  }

  private String clean(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String resolveCompanyStateCode(Company company) {
    if (company == null) {
      return null;
    }
    return gstService.normalizeStateCode(company.getStateCode());
  }

  private String resolveSupplierStateCode(Supplier supplier) {
    if (supplier == null) {
      return null;
    }
    String explicit = gstService.normalizeStateCode(supplier.getStateCode());
    if (StringUtils.hasText(explicit)) {
      return explicit;
    }
    String inferred = inferStateCodeFromGstNumber(supplier.getGstNumber());
    return gstService.normalizeStateCode(inferred);
  }

  private String inferStateCodeFromGstNumber(String gstNumber) {
    if (!StringUtils.hasText(gstNumber)) {
      return null;
    }
    String normalized = gstNumber.trim().toUpperCase(java.util.Locale.ROOT);
    if (normalized.length() < 2) {
      return null;
    }
    return normalized.substring(0, 2);
  }

  private java.time.LocalDate resolvePurchaseDueDate(
      java.time.LocalDate invoiceDate, Supplier supplier) {
    if (invoiceDate == null || supplier == null) {
      return null;
    }
    SupplierPaymentTerms paymentTerms = supplier.getPaymentTerms();
    if (paymentTerms == null) {
      return null;
    }
    return invoiceDate.plusDays(paymentTerms.dueDays());
  }

  private void transitionPurchaseOrderStatus(
      PurchaseOrder purchaseOrder,
      PurchaseOrderStatus targetStatus,
      String reasonCode,
      String reason) {
    if (purchaseOrderService == null) {
      purchaseOrder.setStatus(targetStatus);
      return;
    }
    purchaseOrderService.transitionStatus(purchaseOrder, targetStatus, reasonCode, reason);
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
      String notes,
      BigDecimal cgstAmount,
      BigDecimal sgstAmount,
      BigDecimal igstAmount) {}
}
