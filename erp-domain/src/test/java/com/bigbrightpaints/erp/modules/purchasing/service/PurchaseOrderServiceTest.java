package com.bigbrightpaints.erp.modules.purchasing.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrder;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderStatus;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderStatusHistory;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderStatusHistoryRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierStatus;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderStatusHistoryResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderVoidRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class PurchaseOrderServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;
    @Mock
    private RawMaterialRepository rawMaterialRepository;
    @Mock
    private CompanyEntityLookup companyEntityLookup;
    @Mock
    private PurchaseOrderStatusHistoryRepository purchaseOrderStatusHistoryRepository;

    private PurchaseOrderService purchaseOrderService;
    private Company company;
    private Supplier supplier;
    private RawMaterial rawMaterial;

    @BeforeEach
    void setUp() {
        purchaseOrderService = new PurchaseOrderService(
                companyContextService,
                purchaseOrderRepository,
                rawMaterialRepository,
                companyEntityLookup,
                new PurchaseResponseMapper(),
                purchaseOrderStatusHistoryRepository
        );

        company = new Company();
        ReflectionTestUtils.setField(company, "id", 1L);
        company.setName("Test Company");

        supplier = new Supplier();
        ReflectionTestUtils.setField(supplier, "id", 11L);
        supplier.setCompany(company);
        supplier.setName("Supplier A");
        supplier.setCode("SUP-A");
        supplier.setStatus(SupplierStatus.ACTIVE);

        rawMaterial = new RawMaterial();
        ReflectionTestUtils.setField(rawMaterial, "id", 21L);
        rawMaterial.setCompany(company);
        rawMaterial.setName("Resin");
        rawMaterial.setSku("RM-21");
        rawMaterial.setUnitType("KG");

        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("createPurchaseOrder creates DRAFT status and records initial status history")
    void createPurchaseOrder_createsDraftAndHistory() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireSupplier(company, 11L)).thenReturn(supplier);
        when(purchaseOrderRepository.lockByCompanyAndOrderNumberIgnoreCase(company, "PO-1001"))
                .thenReturn(Optional.empty());
        when(companyEntityLookup.lockActiveRawMaterial(company, 21L)).thenReturn(rawMaterial);

        when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenAnswer(invocation -> {
            PurchaseOrder order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 1001L);
            ReflectionTestUtils.setField(order, "createdAt", Instant.parse("2026-03-01T10:00:00Z"));
            return order;
        });

        PurchaseOrderRequest request = new PurchaseOrderRequest(
                11L,
                "PO-1001",
                LocalDate.of(2026, 3, 1),
                "Initial order",
                List.of(new PurchaseOrderLineRequest(
                        21L,
                        new BigDecimal("5.0000"),
                        "KG",
                        new BigDecimal("10.00"),
                        "line note"
                ))
        );

        PurchaseOrderResponse response = purchaseOrderService.createPurchaseOrder(request);

        assertThat(response.status()).isEqualTo("DRAFT");

        ArgumentCaptor<PurchaseOrderStatusHistory> historyCaptor = ArgumentCaptor.forClass(PurchaseOrderStatusHistory.class);
        verify(purchaseOrderStatusHistoryRepository).save(historyCaptor.capture());

        PurchaseOrderStatusHistory history = historyCaptor.getValue();
        assertThat(history.getFromStatus()).isNull();
        assertThat(history.getToStatus()).isEqualTo("DRAFT");
        assertThat(history.getReasonCode()).isEqualTo("PURCHASE_ORDER_CREATED");
        assertThat(history.getReason()).isEqualTo("Purchase order created");
        assertThat(history.getChangedBy()).isEqualTo(SecurityActorResolver.UNKNOWN_AUTH_ACTOR);
    }

    @Test
    @DisplayName("createPurchaseOrder rejects inactive catalog-backed raw materials")
    void createPurchaseOrder_rejectsInactiveCatalogRawMaterial() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireSupplier(company, 11L)).thenReturn(supplier);
        when(purchaseOrderRepository.lockByCompanyAndOrderNumberIgnoreCase(company, "PO-1003"))
                .thenReturn(Optional.empty());
        when(companyEntityLookup.lockActiveRawMaterial(company, 21L)).thenThrow(
                new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE, "Catalog item is inactive for raw material RM-21"));

        PurchaseOrderRequest request = new PurchaseOrderRequest(
                11L,
                "PO-1003",
                LocalDate.of(2026, 3, 1),
                "Inactive item order",
                List.of(new PurchaseOrderLineRequest(
                        21L,
                        new BigDecimal("5.0000"),
                        "KG",
                        new BigDecimal("10.00"),
                        "line note"
                ))
        );

        assertThatThrownBy(() -> purchaseOrderService.createPurchaseOrder(request))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_INVALID_STATE))
                .hasMessageContaining("inactive");

        verify(purchaseOrderRepository, never()).save(any(PurchaseOrder.class));
    }

    @Test
    @DisplayName("createPurchaseOrder rejects unknown raw materials before saving")
    void createPurchaseOrder_rejectsUnknownRawMaterial() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireSupplier(company, 11L)).thenReturn(supplier);
        when(purchaseOrderRepository.lockByCompanyAndOrderNumberIgnoreCase(company, "PO-1004"))
                .thenReturn(Optional.empty());
        when(companyEntityLookup.lockActiveRawMaterial(company, 21L))
                .thenThrow(new IllegalArgumentException("Raw material not found: id=21"));

        PurchaseOrderRequest request = new PurchaseOrderRequest(
                11L,
                "PO-1004",
                LocalDate.of(2026, 3, 1),
                "Unknown item order",
                List.of(new PurchaseOrderLineRequest(
                        21L,
                        new BigDecimal("5.0000"),
                        "KG",
                        new BigDecimal("10.00"),
                        "line note"
                ))
        );

        assertThatThrownBy(() -> purchaseOrderService.createPurchaseOrder(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Raw material not found");

        verify(purchaseOrderRepository, never()).save(any(PurchaseOrder.class));
    }

    @Test
    @DisplayName("createPurchaseOrder rejects suppliers that are still reference-only")
    void createPurchaseOrder_rejectsReferenceOnlySupplierWithExplicitReason() {
        supplier.setStatus(SupplierStatus.PENDING);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireSupplier(company, 11L)).thenReturn(supplier);

        PurchaseOrderRequest request = new PurchaseOrderRequest(
                11L,
                "PO-1002",
                LocalDate.of(2026, 3, 1),
                "Reference-only supplier order",
                List.of(new PurchaseOrderLineRequest(
                        21L,
                        new BigDecimal("5.0000"),
                        "KG",
                        new BigDecimal("10.00"),
                        "line note"
                ))
        );

        assertThatThrownBy(() -> purchaseOrderService.createPurchaseOrder(request))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_INVALID_STATE))
                .hasMessageContaining("pending approval")
                .hasMessageContaining("reference only");

        verify(purchaseOrderRepository, never()).save(any(PurchaseOrder.class));
    }

    @Test
    @DisplayName("approvePurchaseOrder transitions DRAFT to APPROVED")
    void approvePurchaseOrder_transitionsDraftToApproved() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        PurchaseOrder purchaseOrder = buildOrder(2001L, PurchaseOrderStatus.DRAFT);
        when(purchaseOrderRepository.lockByCompanyAndId(company, 2001L)).thenReturn(Optional.of(purchaseOrder));
        when(purchaseOrderRepository.save(purchaseOrder)).thenReturn(purchaseOrder);

        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("alice", "pwd"));

        PurchaseOrderResponse response = purchaseOrderService.approvePurchaseOrder(2001L);

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(purchaseOrder.getStatus()).isEqualTo("APPROVED");

        ArgumentCaptor<PurchaseOrderStatusHistory> historyCaptor = ArgumentCaptor.forClass(PurchaseOrderStatusHistory.class);
        verify(purchaseOrderStatusHistoryRepository).save(historyCaptor.capture());
        PurchaseOrderStatusHistory history = historyCaptor.getValue();
        assertThat(history.getFromStatus()).isEqualTo("DRAFT");
        assertThat(history.getToStatus()).isEqualTo("APPROVED");
        assertThat(history.getReasonCode()).isEqualTo("PURCHASE_ORDER_APPROVED");
        assertThat(history.getChangedBy()).isEqualTo("alice");
    }

    @Test
    @DisplayName("voidPurchaseOrder from APPROVED requires reason code and records transition history")
    void voidPurchaseOrder_fromApprovedWithReasonCode_recordsHistory() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        PurchaseOrder purchaseOrder = buildOrder(3001L, PurchaseOrderStatus.APPROVED);
        when(purchaseOrderRepository.lockByCompanyAndId(company, 3001L)).thenReturn(Optional.of(purchaseOrder));
        when(purchaseOrderRepository.save(purchaseOrder)).thenReturn(purchaseOrder);

        PurchaseOrderResponse response = purchaseOrderService.voidPurchaseOrder(
                3001L,
                new PurchaseOrderVoidRequest("SUPPLIER_CANCELLED", "Supplier cancelled")
        );

        assertThat(response.status()).isEqualTo("VOID");

        ArgumentCaptor<PurchaseOrderStatusHistory> historyCaptor = ArgumentCaptor.forClass(PurchaseOrderStatusHistory.class);
        verify(purchaseOrderStatusHistoryRepository).save(historyCaptor.capture());

        PurchaseOrderStatusHistory history = historyCaptor.getValue();
        assertThat(history.getFromStatus()).isEqualTo("APPROVED");
        assertThat(history.getToStatus()).isEqualTo("VOID");
        assertThat(history.getReasonCode()).isEqualTo("SUPPLIER_CANCELLED");
        assertThat(history.getReason()).isEqualTo("Supplier cancelled");
    }

    @Test
    @DisplayName("voidPurchaseOrder rejects transition from PARTIALLY_RECEIVED")
    void voidPurchaseOrder_fromPartiallyReceived_throws() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        PurchaseOrder purchaseOrder = buildOrder(3002L, PurchaseOrderStatus.PARTIALLY_RECEIVED);
        when(purchaseOrderRepository.lockByCompanyAndId(company, 3002L)).thenReturn(Optional.of(purchaseOrder));

        assertThatThrownBy(() -> purchaseOrderService.voidPurchaseOrder(
                3002L,
                new PurchaseOrderVoidRequest("INVALID", "Cannot void after receipt")
        ))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_INVALID_STATE))
                .hasMessageContaining("Invalid purchase order state transition");

        verify(purchaseOrderRepository, never()).save(any(PurchaseOrder.class));
    }

    @Test
    @DisplayName("approvePurchaseOrder rejects transition from VOID")
    void approvePurchaseOrder_fromVoid_throws() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        PurchaseOrder purchaseOrder = buildOrder(3003L, PurchaseOrderStatus.VOID);
        when(purchaseOrderRepository.lockByCompanyAndId(company, 3003L)).thenReturn(Optional.of(purchaseOrder));

        assertThatThrownBy(() -> purchaseOrderService.approvePurchaseOrder(3003L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_INVALID_STATE))
                .hasMessageContaining("Invalid purchase order state transition");
    }

    @Test
    @DisplayName("closePurchaseOrder requires INVOICED status")
    void closePurchaseOrder_requiresInvoiced() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        PurchaseOrder purchaseOrder = buildOrder(3004L, PurchaseOrderStatus.FULLY_RECEIVED);
        when(purchaseOrderRepository.lockByCompanyAndId(company, 3004L)).thenReturn(Optional.of(purchaseOrder));

        assertThatThrownBy(() -> purchaseOrderService.closePurchaseOrder(3004L))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_INVALID_STATE))
                .hasMessageContaining("Invalid purchase order state transition");

        verify(purchaseOrderRepository, never()).save(any(PurchaseOrder.class));
    }

    @Test
    @DisplayName("closePurchaseOrder transitions INVOICED to CLOSED")
    void closePurchaseOrder_transitionsInvoicedToClosed() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        PurchaseOrder purchaseOrder = buildOrder(3005L, PurchaseOrderStatus.INVOICED);
        when(purchaseOrderRepository.lockByCompanyAndId(company, 3005L)).thenReturn(Optional.of(purchaseOrder));
        when(purchaseOrderRepository.save(purchaseOrder)).thenReturn(purchaseOrder);

        PurchaseOrderResponse response = purchaseOrderService.closePurchaseOrder(3005L);

        assertThat(response.status()).isEqualTo("CLOSED");

        ArgumentCaptor<PurchaseOrderStatusHistory> historyCaptor = ArgumentCaptor.forClass(PurchaseOrderStatusHistory.class);
        verify(purchaseOrderStatusHistoryRepository).save(historyCaptor.capture());
        PurchaseOrderStatusHistory history = historyCaptor.getValue();
        assertThat(history.getFromStatus()).isEqualTo("INVOICED");
        assertThat(history.getToStatus()).isEqualTo("CLOSED");
        assertThat(history.getReasonCode()).isEqualTo("PURCHASE_ORDER_CLOSED");
    }

    @Test
    @DisplayName("getPurchaseOrderTimeline returns chronological history")
    void getPurchaseOrderTimeline_returnsChronologicalHistory() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        PurchaseOrder purchaseOrder = buildOrder(4001L, PurchaseOrderStatus.APPROVED);
        PurchaseOrderStatusHistory created = history(purchaseOrder, 1L, null, "DRAFT", "PURCHASE_ORDER_CREATED", "created", "alice");
        PurchaseOrderStatusHistory approved = history(purchaseOrder, 2L, "DRAFT", "APPROVED", "PURCHASE_ORDER_APPROVED", "approved", "bob");

        when(purchaseOrderRepository.findByCompanyAndId(company, 4001L)).thenReturn(Optional.of(purchaseOrder));
        when(purchaseOrderStatusHistoryRepository.findTimeline(company, purchaseOrder)).thenReturn(List.of(created, approved));

        List<PurchaseOrderStatusHistoryResponse> timeline = purchaseOrderService.getPurchaseOrderTimeline(4001L);

        assertThat(timeline).hasSize(2);
        assertThat(timeline.get(0).toStatus()).isEqualTo("DRAFT");
        assertThat(timeline.get(1).toStatus()).isEqualTo("APPROVED");
        assertThat(timeline.get(1).changedBy()).isEqualTo("bob");
    }

    private PurchaseOrder buildOrder(Long id, PurchaseOrderStatus status) {
        PurchaseOrder order = new PurchaseOrder();
        ReflectionTestUtils.setField(order, "id", id);
        order.setCompany(company);
        order.setSupplier(supplier);
        order.setOrderNumber("PO-" + id);
        order.setOrderDate(LocalDate.of(2026, 3, 1));
        order.setStatus(status);

        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setPurchaseOrder(order);
        line.setRawMaterial(rawMaterial);
        line.setQuantity(new BigDecimal("5.0000"));
        line.setUnit("KG");
        line.setCostPerUnit(new BigDecimal("10.00"));
        line.setLineTotal(new BigDecimal("50.00"));
        order.getLines().add(line);
        return order;
    }

    private PurchaseOrderStatusHistory history(PurchaseOrder order,
                                               Long id,
                                               String from,
                                               String to,
                                               String reasonCode,
                                               String reason,
                                               String changedBy) {
        PurchaseOrderStatusHistory history = new PurchaseOrderStatusHistory();
        ReflectionTestUtils.setField(history, "id", id);
        history.setCompany(company);
        history.setPurchaseOrder(order);
        history.setFromStatus(from);
        history.setToStatus(to);
        history.setReasonCode(reasonCode);
        history.setReason(reason);
        history.setChangedBy(changedBy);
        history.setChangedAt(Instant.now());
        return history;
    }
}
