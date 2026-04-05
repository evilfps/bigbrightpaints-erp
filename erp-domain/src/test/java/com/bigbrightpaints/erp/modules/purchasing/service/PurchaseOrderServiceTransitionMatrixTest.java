package com.bigbrightpaints.erp.modules.purchasing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
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
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
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
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierStatus;

@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceTransitionMatrixTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private PurchaseOrderRepository purchaseOrderRepository;
  @Mock private CompanyScopedPurchasingLookupService purchasingLookupService;
  @Mock private CompanyScopedInventoryLookupService inventoryLookupService;
  @Mock private PurchaseOrderStatusHistoryRepository purchaseOrderStatusHistoryRepository;

  private PurchaseOrderService purchaseOrderService;
  private Company company;
  private Supplier supplier;
  private RawMaterial rawMaterial;

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

    company = new Company();
    ReflectionTestUtils.setField(company, "id", 1L);

    supplier = new Supplier();
    ReflectionTestUtils.setField(supplier, "id", 10L);
    supplier.setCompany(company);
    supplier.setStatus(SupplierStatus.ACTIVE);

    rawMaterial = new RawMaterial();
    ReflectionTestUtils.setField(rawMaterial, "id", 20L);
    rawMaterial.setCompany(company);
    rawMaterial.setName("Resin");
    rawMaterial.setSku("RM-20");
    rawMaterial.setUnitType("KG");
  }

  @Test
  @DisplayName("transition matrix accepts full canonical happy path")
  void transitionMatrix_acceptsCanonicalPath() {
    PurchaseOrder order = order(100L, PurchaseOrderStatus.DRAFT);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(purchaseOrderRepository.lockByCompanyAndId(company, 100L)).thenReturn(Optional.of(order));
    when(purchaseOrderRepository.save(order)).thenReturn(order);

    purchaseOrderService.approvePurchaseOrder(100L);
    assertThat(order.getStatusEnum()).isEqualTo(PurchaseOrderStatus.APPROVED);

    boolean moved =
        purchaseOrderService.transitionStatus(
            order, PurchaseOrderStatus.PARTIALLY_RECEIVED, "GRN_PARTIAL", "partial receipt");
    assertThat(moved).isTrue();
    assertThat(order.getStatusEnum()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);

    moved =
        purchaseOrderService.transitionStatus(
            order, PurchaseOrderStatus.FULLY_RECEIVED, "GRN_COMPLETE", "fully received");
    assertThat(moved).isTrue();
    assertThat(order.getStatusEnum()).isEqualTo(PurchaseOrderStatus.FULLY_RECEIVED);

    moved =
        purchaseOrderService.transitionStatus(
            order, PurchaseOrderStatus.INVOICED, "INVOICE_POSTED", "invoice posted");
    assertThat(moved).isTrue();
    assertThat(order.getStatusEnum()).isEqualTo(PurchaseOrderStatus.INVOICED);

    purchaseOrderService.closePurchaseOrder(100L);
    assertThat(order.getStatusEnum()).isEqualTo(PurchaseOrderStatus.CLOSED);

    ArgumentCaptor<PurchaseOrderStatusHistory> historyCaptor =
        ArgumentCaptor.forClass(PurchaseOrderStatusHistory.class);
    verify(purchaseOrderStatusHistoryRepository, atLeast(5)).save(historyCaptor.capture());
    List<PurchaseOrderStatusHistory> entries = historyCaptor.getAllValues();
    assertThat(entries.getLast().getToStatus()).isEqualTo("CLOSED");
  }

  @Test
  @DisplayName("transition matrix rejects skipped DRAFT to FULLY_RECEIVED")
  void transitionMatrix_rejectsSkippedDraftToFullyReceived() {
    PurchaseOrder order = order(101L, PurchaseOrderStatus.DRAFT);

    assertThatThrownBy(
            () ->
                purchaseOrderService.transitionStatus(
                    order, PurchaseOrderStatus.FULLY_RECEIVED, "INVALID", "skip states"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BUSINESS_INVALID_STATE));

    verify(purchaseOrderStatusHistoryRepository, never())
        .save(any(PurchaseOrderStatusHistory.class));
  }

  @Test
  @DisplayName("transition matrix rejects closed to any further status")
  void transitionMatrix_rejectsFromClosed() {
    PurchaseOrder order = order(102L, PurchaseOrderStatus.CLOSED);

    assertThatThrownBy(
            () ->
                purchaseOrderService.transitionStatus(
                    order, PurchaseOrderStatus.INVOICED, "INVALID", "cannot reopen"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BUSINESS_INVALID_STATE));
  }

  @Test
  @DisplayName("transition matrix rejects void to approved")
  void transitionMatrix_rejectsVoidToApproved() {
    PurchaseOrder order = order(103L, PurchaseOrderStatus.VOID);

    assertThatThrownBy(
            () ->
                purchaseOrderService.transitionStatus(
                    order, PurchaseOrderStatus.APPROVED, "INVALID", "void cannot approve"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BUSINESS_INVALID_STATE));
  }

  @Test
  @DisplayName("transition matrix no-op transition returns false and does not write history")
  void transitionMatrix_noopTransitionReturnsFalse() {
    PurchaseOrder order = order(104L, PurchaseOrderStatus.APPROVED);

    boolean transitioned =
        purchaseOrderService.transitionStatus(
            order, PurchaseOrderStatus.APPROVED, "NOOP", "same status");

    assertThat(transitioned).isFalse();
    verify(purchaseOrderStatusHistoryRepository, never())
        .save(any(PurchaseOrderStatusHistory.class));
  }

  private PurchaseOrder order(Long id, PurchaseOrderStatus status) {
    PurchaseOrder order = new PurchaseOrder();
    ReflectionTestUtils.setField(order, "id", id);
    order.setCompany(company);
    order.setSupplier(supplier);
    order.setOrderNumber("PO-" + id);
    order.setOrderDate(LocalDate.of(2026, 3, 2));
    order.setStatus(status);

    PurchaseOrderLine line = new PurchaseOrderLine();
    line.setPurchaseOrder(order);
    line.setRawMaterial(rawMaterial);
    line.setQuantity(new BigDecimal("10.0000"));
    line.setUnit("KG");
    line.setCostPerUnit(new BigDecimal("11.00"));
    line.setLineTotal(new BigDecimal("110.00"));
    order.getLines().add(line);
    return order;
  }
}
