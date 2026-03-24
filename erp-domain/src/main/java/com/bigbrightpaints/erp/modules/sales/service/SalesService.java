package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.accounting.service.GstService;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTaskRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceNumberService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequestRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.PromotionRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderStatusHistoryRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesTargetRepository;
import com.bigbrightpaints.erp.modules.sales.dto.CreditRequestDto;
import com.bigbrightpaints.erp.modules.sales.dto.CreditRequestRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DealerDto;
import com.bigbrightpaints.erp.modules.sales.dto.DealerRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmResponse;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchMarkerReconciliationResponse;
import com.bigbrightpaints.erp.modules.sales.dto.PromotionDto;
import com.bigbrightpaints.erp.modules.sales.dto.PromotionRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesDashboardDto;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderDto;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderSearchFilters;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderStatusHistoryDto;
import com.bigbrightpaints.erp.modules.sales.dto.SalesTargetDto;
import com.bigbrightpaints.erp.modules.sales.dto.SalesTargetRequest;
import com.bigbrightpaints.erp.shared.dto.PageResponse;
import com.bigbrightpaints.erp.modules.sales.util.SalesOrderReference;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.StringUtils;

@Service
public class SalesService {

    private final SalesOrderCrudService salesOrderCrudService;
    private final SalesOrderLifecycleService salesOrderLifecycleService;
    private final SalesDealerCrudService salesDealerCrudService;
    private final SalesDispatchReconciliationService salesDispatchReconciliationService;
    private final SalesDashboardService salesDashboardService;
    private final SalesCoreEngine salesCoreEngine;

    @Autowired
    public SalesService(SalesOrderCrudService salesOrderCrudService,
                        SalesOrderLifecycleService salesOrderLifecycleService,
                        SalesDealerCrudService salesDealerCrudService,
                        SalesDispatchReconciliationService salesDispatchReconciliationService,
                        SalesDashboardService salesDashboardService,
                        SalesCoreEngine salesCoreEngine) {
        this.salesOrderCrudService = salesOrderCrudService;
        this.salesOrderLifecycleService = salesOrderLifecycleService;
        this.salesDealerCrudService = salesDealerCrudService;
        this.salesDispatchReconciliationService = salesDispatchReconciliationService;
        this.salesDashboardService = salesDashboardService;
        this.salesCoreEngine = salesCoreEngine;
    }

    public SalesService(CompanyContextService companyContextService,
                        DealerRepository dealerRepository,
                        SalesOrderRepository salesOrderRepository,
                        SalesOrderStatusHistoryRepository salesOrderStatusHistoryRepository,
                        PromotionRepository promotionRepository,
                        SalesTargetRepository salesTargetRepository,
                        CreditRequestRepository creditRequestRepository,
                        OrderNumberService orderNumberService,
                        ApplicationEventPublisher eventPublisher,
                        ProductionProductRepository productionProductRepository,
                        DealerLedgerService dealerLedgerService,
                        FinishedGoodRepository finishedGoodRepository,
                        FinishedGoodBatchRepository finishedGoodBatchRepository,
                        AccountRepository accountRepository,
                        CompanyEntityLookup companyEntityLookup,
                        PackagingSlipRepository packagingSlipRepository,
                        FinishedGoodsService finishedGoodsService,
                        AccountingService accountingService,
                        AccountingFacade accountingFacade,
                        JournalEntryRepository journalEntryRepository,
                        InvoiceNumberService invoiceNumberService,
                        InvoiceRepository invoiceRepository,
                        FactoryTaskRepository factoryTaskRepository,
                        CompanyDefaultAccountsService companyDefaultAccountsService,
                        CompanyAccountingSettingsService companyAccountingSettingsService,
                        GstService gstService,
                        CreditLimitOverrideService creditLimitOverrideService,
                        AuditService auditService,
                        CompanyClock companyClock,
                        PlatformTransactionManager transactionManager,
                        MeterRegistry meterRegistry) {
        SalesCoreEngine engine = new SalesCoreEngine(
                companyContextService,
                dealerRepository,
                salesOrderRepository,
                salesOrderStatusHistoryRepository,
                promotionRepository,
                salesTargetRepository,
                creditRequestRepository,
                orderNumberService,
                eventPublisher,
                productionProductRepository,
                dealerLedgerService,
                finishedGoodRepository,
                finishedGoodBatchRepository,
                accountRepository,
                companyEntityLookup,
                packagingSlipRepository,
                finishedGoodsService,
                accountingService,
                accountingFacade,
                journalEntryRepository,
                invoiceNumberService,
                invoiceRepository,
                factoryTaskRepository,
                companyDefaultAccountsService,
                companyAccountingSettingsService,
                gstService,
                creditLimitOverrideService,
                auditService,
                companyClock,
                transactionManager,
                meterRegistry
        );
        this.salesCoreEngine = engine;
        SalesIdempotencyService idempotencyService = new SalesIdempotencyService(engine);
        this.salesOrderCrudService = new SalesOrderCrudService(engine, idempotencyService);
        this.salesOrderLifecycleService = new SalesOrderLifecycleService(engine);
        this.salesDealerCrudService = new SalesDealerCrudService(engine);
        this.salesDispatchReconciliationService = new SalesDispatchReconciliationService(engine);
        this.salesDashboardService = new SalesDashboardService(engine);
    }

    public List<DealerDto> listDealers() {
        return salesDealerCrudService.listDealers();
    }

    public DealerDto createDealer(DealerRequest request) {
        return salesDealerCrudService.createDealer(request);
    }

    public DealerDto updateDealer(Long id, DealerRequest request) {
        return salesDealerCrudService.updateDealer(id, request);
    }

    public void deleteDealer(Long id) {
        salesDealerCrudService.deleteDealer(id);
    }

    public List<SalesOrderDto> listOrders(String status, int page, int size) {
        return salesOrderCrudService.listOrders(status, page, size);
    }

    public List<SalesOrderDto> listOrders(String status, Long dealerId, int page, int size) {
        return salesOrderCrudService.listOrders(status, dealerId, page, size);
    }

    public List<SalesOrderDto> listOrders(String status) {
        return salesOrderCrudService.listOrders(status);
    }

    public List<SalesOrderDto> listOrders(String status, Long dealerId) {
        return salesOrderCrudService.listOrders(status, dealerId);
    }

    public SalesDashboardDto getDashboard() {
        return salesDashboardService.getDashboard();
    }

    public SalesOrderDto createOrder(SalesOrderRequest request) {
        return salesOrderCrudService.createOrder(request);
    }

    public SalesOrderDto updateOrder(Long id, SalesOrderRequest request) {
        return salesOrderCrudService.updateOrder(id, request);
    }

    public PageResponse<SalesOrderDto> searchOrders(SalesOrderSearchFilters filters) {
        return salesCoreEngine.searchOrders(filters);
    }

    public List<SalesOrderStatusHistoryDto> orderTimeline(Long id) {
        return salesOrderLifecycleService.orderTimeline(id);
    }

    public void deleteOrder(Long id) {
        salesOrderCrudService.deleteOrder(id);
    }

    public SalesOrderDto confirmOrder(Long id) {
        return salesOrderLifecycleService.confirmOrder(id);
    }

    public SalesOrderDto cancelOrder(Long id, String reason) {
        return salesOrderLifecycleService.cancelOrder(id, reason);
    }

    public SalesOrderDto updateStatus(Long id, String status) {
        return salesOrderLifecycleService.updateStatus(id, status);
    }

    public SalesOrderDto updateStatusInternal(Long id, String status) {
        return salesOrderLifecycleService.updateStatusInternal(id, status);
    }

    public void updateOrchestratorWorkflowStatus(Long id, String status) {
        salesOrderLifecycleService.updateOrchestratorWorkflowStatus(id, status);
    }

    public boolean hasDispatchConfirmation(Long id) {
        return salesOrderLifecycleService.hasDispatchConfirmation(id);
    }

    public void attachTraceId(Long id, String traceId) {
        salesOrderLifecycleService.attachTraceId(id, traceId);
    }

    public SalesOrder getOrderWithItems(Long id) {
        return salesOrderCrudService.getOrderWithItems(id);
    }

    public DispatchMarkerReconciliationResponse reconcileStaleOrderLevelMarkers(int limit) {
        return salesDispatchReconciliationService.reconcileStaleOrderLevelMarkers(limit);
    }

    public DispatchConfirmResponse confirmDispatch(DispatchConfirmRequest request) {
        return salesDispatchReconciliationService.confirmDispatch(request);
    }

    public List<PromotionDto> listPromotions() {
        return salesCoreEngine.listPromotions();
    }

    public PromotionDto createPromotion(PromotionRequest request) {
        return salesCoreEngine.createPromotion(request);
    }

    public PromotionDto updatePromotion(Long id, PromotionRequest request) {
        return salesCoreEngine.updatePromotion(id, request);
    }

    public void deletePromotion(Long id) {
        salesCoreEngine.deletePromotion(id);
    }

    public List<SalesTargetDto> listTargets() {
        return salesCoreEngine.listTargets();
    }

    public SalesTargetDto createTarget(SalesTargetRequest request) {
        return salesCoreEngine.createTarget(request);
    }

    public SalesTargetDto updateTarget(Long id, SalesTargetRequest request) {
        return salesCoreEngine.updateTarget(id, request);
    }

    public void deleteTarget(Long id, String reason) {
        salesCoreEngine.deleteTarget(id, reason);
    }

    public List<CreditRequestDto> listCreditRequests() {
        return salesDealerCrudService.listCreditRequests();
    }

    public CreditRequestDto createCreditRequest(CreditRequestRequest request) {
        return salesDealerCrudService.createCreditRequest(request);
    }

    public CreditRequestDto updateCreditRequest(Long id, CreditRequestRequest request) {
        return salesDealerCrudService.updateCreditRequest(id, request);
    }

    public CreditRequestDto approveCreditRequest(Long id, String decisionReason) {
        return salesDealerCrudService.approveCreditRequest(id, decisionReason);
    }

    public CreditRequestDto rejectCreditRequest(Long id, String decisionReason) {
        return salesDealerCrudService.rejectCreditRequest(id, decisionReason);
    }

    @SuppressWarnings("unused")
    private String resolveDispatchExceptionReasonCode(boolean hasCreditException,
                                                      boolean hasPriceOverride,
                                                      boolean hasDiscountOverride,
                                                      boolean hasTaxOverride,
                                                      boolean hasAnyLineOverride) {
        int lineOverrideKinds = 0;
        if (hasPriceOverride) {
            lineOverrideKinds++;
        }
        if (hasDiscountOverride) {
            lineOverrideKinds++;
        }
        if (hasTaxOverride) {
            lineOverrideKinds++;
        }
        if (!hasCreditException && lineOverrideKinds == 0) {
            return null;
        }
        if (hasCreditException && lineOverrideKinds == 0) {
            return "CREDIT_LIMIT_EXCEPTION";
        }
        if (!hasCreditException && lineOverrideKinds == 1) {
            if (hasPriceOverride) {
                return "PRICE_OVERRIDE";
            }
            if (hasDiscountOverride) {
                return "DISCOUNT_OVERRIDE";
            }
            return "TAX_OVERRIDE";
        }
        if (!hasCreditException && hasAnyLineOverride && lineOverrideKinds == 0) {
            return "LINE_OVERRIDE";
        }
        return "COMPOSITE_OVERRIDE";
    }

    @SuppressWarnings("unused")
    private String formatDispatchNotesWithOverrideReason(String dispatchNotes, String overrideReason) {
        String base = StringUtils.hasText(dispatchNotes) ? dispatchNotes.trim() : "";
        String reason = StringUtils.hasText(overrideReason) ? overrideReason.trim() : "";
        if (!StringUtils.hasText(reason)) {
            return base;
        }
        String combined = base.isEmpty()
                ? "Override reason: " + reason
                : base + " | Override reason: " + reason;
        if (combined.length() > 1000) {
            combined = combined.substring(0, 1000);
        }
        return combined;
    }

    @SuppressWarnings("unused")
    private String normalizeTraceId(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return null;
        }
        return traceId.trim();
    }

    @SuppressWarnings("unused")
    private String normalizeStatusToken(String status) {
        if (!StringUtils.hasText(status)) {
            return "";
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    @SuppressWarnings("unused")
    private String buildCogsReference(String referenceId) {
        return SalesOrderReference.cogsReference(referenceId);
    }

}
