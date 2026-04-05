package com.bigbrightpaints.erp.modules.purchasing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.service.CompanyScopedInventoryLookupService;
import com.bigbrightpaints.erp.modules.inventory.service.RawMaterialService;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrder;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderStatus;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderStatusHistory;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderStatusHistoryRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierStatus;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderVoidRequest;

@ExtendWith(MockitoExtension.class)
class PurchaseOrderLifecycleTransitionTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private PurchaseOrderRepository purchaseOrderRepository;
  @Mock private CompanyScopedPurchasingLookupService purchasingLookupService;
  @Mock private CompanyScopedInventoryLookupService inventoryLookupService;
  @Mock private PurchaseOrderStatusHistoryRepository purchaseOrderStatusHistoryRepository;
  @Mock private GoodsReceiptRepository goodsReceiptRepository;
  @Mock private RawMaterialService rawMaterialService;
  @Mock private AccountingPeriodService accountingPeriodService;

  private PurchaseOrderService purchaseOrderService;
  private GoodsReceiptService goodsReceiptService;
  private Company company;
  private Supplier supplier;
  private RawMaterial rawMaterial;
  private PurchaseOrder purchaseOrder;

  @BeforeEach
  void setUp() {
    purchaseOrderService =
        new PurchaseOrderService(
            companyContextService,
            purchaseOrderRepository,
            purchasingLookupService,
            inventoryLookupService,
            new PurchaseResponseMapper(),
            purchaseOrderStatusHistoryRepository);

    goodsReceiptService =
        new GoodsReceiptService(
            companyContextService,
            purchaseOrderRepository,
            goodsReceiptRepository,
            rawMaterialService,
            purchasingLookupService,
            inventoryLookupService,
            accountingPeriodService,
            new PurchaseResponseMapper(),
            purchaseOrderService,
            new ResourcelessTransactionManager());

    company = new Company();
    ReflectionTestUtils.setField(company, "id", 1L);

    supplier = new Supplier();
    ReflectionTestUtils.setField(supplier, "id", 10L);
    supplier.setCompany(company);
    supplier.setStatus(SupplierStatus.ACTIVE);

    rawMaterial = new RawMaterial();
    ReflectionTestUtils.setField(rawMaterial, "id", 20L);
    rawMaterial.setCompany(company);
    rawMaterial.setSku("RM-20");
    rawMaterial.setName("Resin");
    rawMaterial.setUnitType("KG");

    purchaseOrder = new PurchaseOrder();
    ReflectionTestUtils.setField(purchaseOrder, "id", 30L);
    purchaseOrder.setCompany(company);
    purchaseOrder.setSupplier(supplier);
    purchaseOrder.setOrderNumber("PO-30");
    purchaseOrder.setOrderDate(LocalDate.of(2026, 3, 2));
    purchaseOrder.setStatus(PurchaseOrderStatus.DRAFT);

    PurchaseOrderLine line = new PurchaseOrderLine();
    line.setPurchaseOrder(purchaseOrder);
    line.setRawMaterial(rawMaterial);
    line.setQuantity(new BigDecimal("10.0000"));
    line.setUnit("KG");
    line.setCostPerUnit(new BigDecimal("12.00"));
    line.setLineTotal(new BigDecimal("120.00"));
    purchaseOrder.getLines().add(line);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
  }

  @Test
  @DisplayName("createGoodsReceipt rejects receipts when purchase order is still DRAFT")
  void createGoodsReceipt_rejectsWhenOrderDraft() {
    when(goodsReceiptRepository.findWithLinesByCompanyAndIdempotencyKey(company, "idem-grn"))
        .thenReturn(Optional.empty());
    when(purchaseOrderRepository.lockByCompanyAndId(company, 30L))
        .thenReturn(Optional.of(purchaseOrder));
    when(goodsReceiptRepository.lockByCompanyAndReceiptNumberIgnoreCase(company, "GRN-30-1"))
        .thenReturn(Optional.empty());

    GoodsReceiptRequest request =
        new GoodsReceiptRequest(
            30L,
            "GRN-30-1",
            LocalDate.of(2026, 3, 3),
            "receipt",
            "idem-grn",
            List.of(
                new GoodsReceiptLineRequest(
                    20L,
                    "BATCH-1",
                    new BigDecimal("5.0000"),
                    "KG",
                    new BigDecimal("12.00"),
                    "notes")));

    assertThatThrownBy(() -> goodsReceiptService.createGoodsReceipt(request))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION))
        .hasMessageContaining("Purchase order is not receivable");
  }

  @Test
  @DisplayName("approvePurchaseOrder must be called before goods receipt and records history")
  void approvePurchaseOrder_enablesGoodsReceiptFlow() {
    when(purchaseOrderRepository.lockByCompanyAndId(company, 30L))
        .thenReturn(Optional.of(purchaseOrder));
    when(purchaseOrderRepository.save(purchaseOrder)).thenReturn(purchaseOrder);

    purchaseOrderService.approvePurchaseOrder(30L);

    assertThat(purchaseOrder.getStatusEnum()).isEqualTo(PurchaseOrderStatus.APPROVED);

    ArgumentCaptor<PurchaseOrderStatusHistory> historyCaptor =
        ArgumentCaptor.forClass(PurchaseOrderStatusHistory.class);
    verify(purchaseOrderStatusHistoryRepository).save(historyCaptor.capture());
    PurchaseOrderStatusHistory history = historyCaptor.getValue();
    assertThat(history.getFromStatus()).isEqualTo("DRAFT");
    assertThat(history.getToStatus()).isEqualTo("APPROVED");
  }

  @Test
  @DisplayName("voidPurchaseOrder can only be executed from DRAFT or APPROVED")
  void voidPurchaseOrder_rejectsAfterReceipt() {
    purchaseOrder.setStatus(PurchaseOrderStatus.PARTIALLY_RECEIVED);
    when(purchaseOrderRepository.lockByCompanyAndId(company, 30L))
        .thenReturn(Optional.of(purchaseOrder));

    assertThatThrownBy(
            () ->
                purchaseOrderService.voidPurchaseOrder(
                    30L, new PurchaseOrderVoidRequest("INVALID", "cannot void")))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BUSINESS_INVALID_STATE));

    verify(purchaseOrderRepository, never()).save(any(PurchaseOrder.class));
  }
}
