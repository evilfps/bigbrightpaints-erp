package com.bigbrightpaints.erp.modules.purchasing.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.service.CompanyScopedInventoryLookupService;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrder;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderStatus;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderStatusHistory;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderStatusHistoryRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderStatusHistoryResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderVoidRequest;

import jakarta.transaction.Transactional;

@Service
public class PurchaseOrderService {

  private final CompanyContextService companyContextService;
  private final PurchaseOrderRepository purchaseOrderRepository;
  private final CompanyScopedPurchasingLookupService purchasingLookupService;
  private final CompanyScopedInventoryLookupService inventoryLookupService;
  private final PurchaseResponseMapper responseMapper;
  private final PurchaseOrderStatusHistoryRepository purchaseOrderStatusHistoryRepository;

  public PurchaseOrderService(
      CompanyContextService companyContextService,
      PurchaseOrderRepository purchaseOrderRepository,
      CompanyScopedPurchasingLookupService purchasingLookupService,
      CompanyScopedInventoryLookupService inventoryLookupService,
      PurchaseResponseMapper responseMapper,
      PurchaseOrderStatusHistoryRepository purchaseOrderStatusHistoryRepository) {
    this.companyContextService = companyContextService;
    this.purchaseOrderRepository = purchaseOrderRepository;
    this.purchasingLookupService = purchasingLookupService;
    this.inventoryLookupService = inventoryLookupService;
    this.responseMapper = responseMapper;
    this.purchaseOrderStatusHistoryRepository = purchaseOrderStatusHistoryRepository;
  }

  public List<PurchaseOrderResponse> listPurchaseOrders() {
    return listPurchaseOrders(null);
  }

  public List<PurchaseOrderResponse> listPurchaseOrders(Long supplierId) {
    Company company = companyContextService.requireCurrentCompany();
    Supplier supplier =
        supplierId != null ? purchasingLookupService.requireSupplier(company, supplierId) : null;
    List<PurchaseOrder> orders =
        supplier == null
            ? purchaseOrderRepository.findByCompanyWithLinesOrderByOrderDateDesc(company)
            : purchaseOrderRepository.findByCompanyAndSupplierWithLinesOrderByOrderDateDesc(
                company, supplier);
    return orders.stream().map(responseMapper::toPurchaseOrderResponse).toList();
  }

  public PurchaseOrderResponse getPurchaseOrder(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    PurchaseOrder order =
        purchaseOrderRepository
            .findByCompanyAndId(company, id)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Purchase order not found"));
    return responseMapper.toPurchaseOrderResponse(order);
  }

  @Transactional
  public PurchaseOrderResponse createPurchaseOrder(PurchaseOrderRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    Supplier supplier = purchasingLookupService.requireSupplier(company, request.supplierId());
    supplier.requireTransactionalUsage("create purchase orders");

    String orderNumber = request.orderNumber().trim();
    purchaseOrderRepository
        .lockByCompanyAndOrderNumberIgnoreCase(company, orderNumber)
        .ifPresent(
            existing -> {
              throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                  "Order number already used for this company");
            });

    List<PurchaseOrderLineRequest> sortedLines =
        request.lines().stream()
            .sorted(java.util.Comparator.comparing(PurchaseOrderLineRequest::rawMaterialId))
            .toList();

    Map<Long, RawMaterial> lockedMaterials = new HashMap<>();
    Set<Long> seenMaterialIds = new HashSet<>();
    for (PurchaseOrderLineRequest lineRequest : sortedLines) {
      RawMaterial rawMaterial = requireMaterial(company, lineRequest.rawMaterialId());
      if (!seenMaterialIds.add(rawMaterial.getId())) {
        throw new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT,
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
    purchaseOrder.setStatus(PurchaseOrderStatus.DRAFT);

    for (PurchaseOrderLineRequest lineRequest : request.lines()) {
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
    recordInitialStatusHistory(saved);
    return responseMapper.toPurchaseOrderResponse(saved);
  }

  @Transactional
  public List<PurchaseOrderStatusHistoryResponse> getPurchaseOrderTimeline(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    PurchaseOrder purchaseOrder =
        purchaseOrderRepository
            .findByCompanyAndId(company, id)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Purchase order not found"));
    return purchaseOrderStatusHistoryRepository.findTimeline(company, purchaseOrder).stream()
        .map(this::toStatusHistoryResponse)
        .toList();
  }

  @Transactional
  public PurchaseOrderResponse approvePurchaseOrder(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    PurchaseOrder purchaseOrder = requirePurchaseOrderForUpdate(company, id);
    transitionStatus(
        purchaseOrder,
        PurchaseOrderStatus.APPROVED,
        "PURCHASE_ORDER_APPROVED",
        "Purchase order approved",
        currentActorIdentity(),
        false);
    PurchaseOrder saved = purchaseOrderRepository.save(purchaseOrder);
    return responseMapper.toPurchaseOrderResponse(saved);
  }

  @Transactional
  public PurchaseOrderResponse voidPurchaseOrder(Long id, PurchaseOrderVoidRequest request) {
    if (request == null || !StringUtils.hasText(request.reasonCode())) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Void reason code is required");
    }
    Company company = companyContextService.requireCurrentCompany();
    PurchaseOrder purchaseOrder = requirePurchaseOrderForUpdate(company, id);
    transitionStatus(
        purchaseOrder,
        PurchaseOrderStatus.VOID,
        request.reasonCode(),
        clean(request.reason()),
        currentActorIdentity(),
        false);
    PurchaseOrder saved = purchaseOrderRepository.save(purchaseOrder);
    return responseMapper.toPurchaseOrderResponse(saved);
  }

  @Transactional
  public PurchaseOrderResponse closePurchaseOrder(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    PurchaseOrder purchaseOrder = requirePurchaseOrderForUpdate(company, id);
    transitionStatus(
        purchaseOrder,
        PurchaseOrderStatus.CLOSED,
        "PURCHASE_ORDER_CLOSED",
        "Purchase order closed",
        currentActorIdentity(),
        false);
    PurchaseOrder saved = purchaseOrderRepository.save(purchaseOrder);
    return responseMapper.toPurchaseOrderResponse(saved);
  }

  public boolean transitionStatus(
      PurchaseOrder purchaseOrder,
      PurchaseOrderStatus targetStatus,
      String reasonCode,
      String reason) {
    return transitionStatus(
        purchaseOrder, targetStatus, reasonCode, reason, currentActorIdentity(), true);
  }

  private boolean transitionStatus(
      PurchaseOrder purchaseOrder,
      PurchaseOrderStatus targetStatus,
      String reasonCode,
      String reason,
      String actor,
      boolean allowNoop) {
    if (purchaseOrder == null || purchaseOrder.getCompany() == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Purchase order not found");
    }
    if (targetStatus == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Target purchase order status is required");
    }
    PurchaseOrderStatus currentStatus =
        purchaseOrder.getStatusEnum() == null
            ? PurchaseOrderStatus.DRAFT
            : purchaseOrder.getStatusEnum();
    if (currentStatus == targetStatus) {
      if (!allowNoop) {
        throw new ApplicationException(
                ErrorCode.BUSINESS_INVALID_STATE,
                "Purchase order already in status " + targetStatus)
            .withDetail("purchaseOrderId", purchaseOrder.getId())
            .withDetail("status", targetStatus.name());
      }
      return false;
    }
    if (!isValidTransition(currentStatus, targetStatus)) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE,
              "Invalid purchase order state transition from "
                  + currentStatus
                  + " to "
                  + targetStatus)
          .withDetail("purchaseOrderId", purchaseOrder.getId())
          .withDetail("fromStatus", currentStatus.name())
          .withDetail("toStatus", targetStatus.name());
    }
    purchaseOrder.setStatus(targetStatus);
    recordStatusHistory(purchaseOrder, currentStatus, targetStatus, reasonCode, reason, actor);
    return true;
  }

  private boolean isValidTransition(PurchaseOrderStatus fromStatus, PurchaseOrderStatus toStatus) {
    if (fromStatus == null || toStatus == null) {
      return false;
    }
    return switch (fromStatus) {
      case DRAFT ->
          toStatus == PurchaseOrderStatus.APPROVED || toStatus == PurchaseOrderStatus.VOID;
      case APPROVED ->
          toStatus == PurchaseOrderStatus.PARTIALLY_RECEIVED
              || toStatus == PurchaseOrderStatus.FULLY_RECEIVED
              || toStatus == PurchaseOrderStatus.VOID;
      case PARTIALLY_RECEIVED -> toStatus == PurchaseOrderStatus.FULLY_RECEIVED;
      case FULLY_RECEIVED -> toStatus == PurchaseOrderStatus.INVOICED;
      case INVOICED -> toStatus == PurchaseOrderStatus.CLOSED;
      case CLOSED, VOID -> false;
    };
  }

  private void recordInitialStatusHistory(PurchaseOrder purchaseOrder) {
    recordStatusHistory(
        purchaseOrder,
        null,
        PurchaseOrderStatus.DRAFT,
        "PURCHASE_ORDER_CREATED",
        "Purchase order created",
        currentActorIdentity());
  }

  private void recordStatusHistory(
      PurchaseOrder purchaseOrder,
      PurchaseOrderStatus fromStatus,
      PurchaseOrderStatus toStatus,
      String reasonCode,
      String reason,
      String actor) {
    if (purchaseOrder == null
        || purchaseOrder.getId() == null
        || purchaseOrder.getCompany() == null) {
      return;
    }
    PurchaseOrderStatusHistory history = new PurchaseOrderStatusHistory();
    history.setCompany(purchaseOrder.getCompany());
    history.setPurchaseOrder(purchaseOrder);
    history.setFromStatus(fromStatus != null ? fromStatus.name() : null);
    history.setToStatus(toStatus != null ? toStatus.name() : purchaseOrder.getStatus());
    history.setReasonCode(
        StringUtils.hasText(reasonCode) ? reasonCode.trim().toUpperCase(Locale.ROOT) : null);
    history.setReason(clean(reason));
    history.setChangedBy(
        StringUtils.hasText(actor)
            ? actor.trim()
            : SecurityActorResolver.resolveActorWithSystemProcessFallback());
    purchaseOrderStatusHistoryRepository.save(history);
  }

  private PurchaseOrderStatusHistoryResponse toStatusHistoryResponse(
      PurchaseOrderStatusHistory history) {
    if (history == null) {
      return null;
    }
    return new PurchaseOrderStatusHistoryResponse(
        history.getId(),
        history.getFromStatus(),
        history.getToStatus(),
        history.getReasonCode(),
        history.getReason(),
        history.getChangedBy(),
        history.getChangedAt());
  }

  private PurchaseOrder requirePurchaseOrderForUpdate(Company company, Long id) {
    return purchaseOrderRepository
        .lockByCompanyAndId(company, id)
        .orElseThrow(
            () ->
                com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "Purchase order not found"));
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

  private BigDecimal currency(BigDecimal value) {
    return MoneyUtils.roundCurrency(value);
  }

  private String clean(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String currentActorIdentity() {
    return SecurityActorResolver.resolveActorWithSystemProcessFallback();
  }
}
