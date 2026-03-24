package com.bigbrightpaints.erp.modules.sales.controller;

import com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipDto;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.modules.sales.dto.*;
import com.bigbrightpaints.erp.modules.sales.service.DealerService;
import com.bigbrightpaints.erp.modules.sales.service.SalesDashboardService;
import com.bigbrightpaints.erp.modules.sales.service.SalesDealerCrudService;
import com.bigbrightpaints.erp.modules.sales.service.SalesDispatchReconciliationService;
import com.bigbrightpaints.erp.modules.sales.service.DispatchMetadataValidator;
import com.bigbrightpaints.erp.modules.sales.service.SalesOrderCrudService;
import com.bigbrightpaints.erp.modules.sales.service.SalesOrderLifecycleService;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.IdempotencyHeaderUtils;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.shared.dto.PageResponse;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class SalesController {

    private final SalesService salesService;
    private final SalesOrderCrudService salesOrderCrudService;
    private final SalesOrderLifecycleService salesOrderLifecycleService;
    private final SalesDealerCrudService salesDealerCrudService;
    private final SalesDispatchReconciliationService salesDispatchReconciliationService;
    private final SalesDashboardService salesDashboardService;
    private final DealerService dealerService;
    private final FinishedGoodsService finishedGoodsService;

    public SalesController(SalesService salesService,
                           SalesOrderCrudService salesOrderCrudService,
                           SalesOrderLifecycleService salesOrderLifecycleService,
                           SalesDealerCrudService salesDealerCrudService,
                           SalesDispatchReconciliationService salesDispatchReconciliationService,
                           SalesDashboardService salesDashboardService,
                           DealerService dealerService,
                           FinishedGoodsService finishedGoodsService) {
        this.salesService = salesService;
        this.salesOrderCrudService = salesOrderCrudService;
        this.salesOrderLifecycleService = salesOrderLifecycleService;
        this.salesDealerCrudService = salesDealerCrudService;
        this.salesDispatchReconciliationService = salesDispatchReconciliationService;
        this.salesDashboardService = salesDashboardService;
        this.dealerService = dealerService;
        this.finishedGoodsService = finishedGoodsService;
    }

    /* Dealers alias - frontend calls /sales/dealers, backend has /dealers */
    @GetMapping("/sales/dealers")
    @PreAuthorize(PortalRoleActionMatrix.ADMIN_SALES_ACCOUNTING)
    public ResponseEntity<ApiResponse<List<DealerResponse>>> listDealers() {
        return ResponseEntity.ok(ApiResponse.success("Dealer directory", dealerService.listDealers()));
    }

    @GetMapping("/sales/dealers/search")
    @PreAuthorize(PortalRoleActionMatrix.ADMIN_SALES_ACCOUNTING)
    public ResponseEntity<ApiResponse<List<DealerLookupResponse>>> searchDealers(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String creditStatus) {
        return ResponseEntity.ok(ApiResponse.success(dealerService.search(query, status, region, creditStatus)));
    }

    /* Sales Orders */
    @GetMapping("/sales/orders")
    @Timed(value = "erp.sales.orders.list", description = "List sales orders")
    @PreAuthorize(PortalRoleActionMatrix.ADMIN_SALES_FACTORY_ACCOUNTING)
    public ResponseEntity<ApiResponse<List<SalesOrderDto>>> orders(@RequestParam(required = false) String status,
                                                                   @RequestParam(required = false) Long dealerId,
                                                                   @RequestParam(defaultValue = "0") int page,
                                                                   @RequestParam(defaultValue = "100") int size) {
        return ResponseEntity.ok(ApiResponse.success(salesOrderCrudService.listOrders(status, dealerId, page, size)));
    }

    @GetMapping("/sales/orders/search")
    @Timed(value = "erp.sales.orders.search", description = "Search sales orders")
    @PreAuthorize(PortalRoleActionMatrix.ADMIN_SALES_FACTORY_ACCOUNTING)
    public ResponseEntity<ApiResponse<PageResponse<SalesOrderDto>>> searchOrders(@RequestParam(required = false) String status,
                                                                                  @RequestParam(required = false) Long dealerId,
                                                                                  @RequestParam(required = false) String orderNumber,
                                                                                  @RequestParam(required = false) String fromDate,
                                                                                  @RequestParam(required = false) String toDate,
                                                                                  @RequestParam(defaultValue = "0") int page,
                                                                                  @RequestParam(defaultValue = "50") int size) {
        SalesOrderSearchFilters filters = new SalesOrderSearchFilters(
                status,
                dealerId,
                orderNumber,
                parseInstant(fromDate, "fromDate"),
                parseInstant(toDate, "toDate"),
                page,
                size);
        return ResponseEntity.ok(ApiResponse.success(salesService.searchOrders(filters)));
    }

    @GetMapping("/sales/dashboard")
    @PreAuthorize(PortalRoleActionMatrix.ADMIN_SALES_FACTORY_ACCOUNTING)
    public ResponseEntity<ApiResponse<SalesDashboardDto>> dashboard() {
        return ResponseEntity.ok(ApiResponse.success("Sales dashboard", salesDashboardService.getDashboard()));
    }

    @PostMapping("/sales/orders")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SalesOrderDto>> createOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String legacyIdempotencyKey,
            @Valid @RequestBody SalesOrderRequest request) {
        SalesOrderRequest resolved = applyOrderIdempotencyKey(request, idempotencyKey, legacyIdempotencyKey);
        return ResponseEntity.ok(ApiResponse.success("Order created", salesOrderCrudService.createOrder(resolved)));
    }

    @PutMapping("/sales/orders/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SalesOrderDto>> updateOrder(@PathVariable Long id,
                                                                   @Valid @RequestBody SalesOrderRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Order updated", salesOrderCrudService.updateOrder(id, request)));
    }

    @DeleteMapping("/sales/orders/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<Void> deleteOrder(@PathVariable Long id) {
        salesOrderCrudService.deleteOrder(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sales/orders/{id}/confirm")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SalesOrderDto>> confirmOrder(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Order confirmed", salesOrderLifecycleService.confirmOrder(id)));
    }

    @PostMapping("/sales/orders/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SalesOrderDto>> cancelOrder(@PathVariable Long id,
                                                                   @RequestBody(required = false) CancelRequest request) {
        String reason = request == null ? null : request.reason();
        String reasonCode = request == null ? null : request.reasonCode();
        return ResponseEntity.ok(ApiResponse.success("Order cancelled", salesOrderLifecycleService.cancelOrder(id, combineCancellationReason(reasonCode, reason))));
    }

    @PatchMapping("/sales/orders/{id}/status")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SalesOrderDto>> updateStatus(@PathVariable Long id,
                                                                    @RequestBody StatusRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Status updated", salesOrderLifecycleService.updateStatus(id, request.status())));
    }

    @GetMapping("/sales/orders/{id}/timeline")
    @PreAuthorize(PortalRoleActionMatrix.ADMIN_SALES_FACTORY_ACCOUNTING)
    public ResponseEntity<ApiResponse<List<SalesOrderStatusHistoryDto>>> orderTimeline(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(salesOrderLifecycleService.orderTimeline(id)));
    }

    public record CancelRequest(String reasonCode, String reason) {}
    public record StatusRequest(String status) {}

    private SalesOrderRequest applyOrderIdempotencyKey(SalesOrderRequest request,
                                                       String idempotencyKeyHeader,
                                                       String legacyIdempotencyKeyHeader) {
        if (request == null) {
            return null;
        }
        String primaryHeader = normalizeIdempotencyHeader(idempotencyKeyHeader);
        String legacyHeader = normalizeIdempotencyHeader(legacyIdempotencyKeyHeader);
        if (primaryHeader != null && legacyHeader != null && !primaryHeader.equals(legacyHeader)) {
            throw new ApplicationException(
                    ErrorCode.VALIDATION_INVALID_INPUT,
                    "Idempotency key mismatch between Idempotency-Key and X-Idempotency-Key headers"
            ).withDetail("idempotencyKey", primaryHeader)
                    .withDetail("legacyIdempotencyKey", legacyHeader);
        }
        String resolvedKey = IdempotencyHeaderUtils.resolveBodyOrHeaderKey(
                request.idempotencyKey(),
                primaryHeader,
                legacyHeader
        );
        if (!StringUtils.hasText(resolvedKey) || StringUtils.hasText(request.idempotencyKey())) {
            return request;
        }
        return new SalesOrderRequest(
                request.dealerId(),
                request.totalAmount(),
                request.currency(),
                request.notes(),
                request.items(),
                request.gstTreatment(),
                request.gstRate(),
                request.gstInclusive(),
                resolvedKey,
                request.paymentMode()
        );
    }

    private String normalizeIdempotencyHeader(String headerValue) {
        if (!StringUtils.hasText(headerValue)) {
            return null;
        }
        return headerValue.trim();
    }

    private String combineCancellationReason(String reasonCode, String reason) {
        String normalizedCode = StringUtils.hasText(reasonCode) ? reasonCode.trim() : null;
        String normalizedReason = StringUtils.hasText(reason) ? reason.trim() : null;
        if (!StringUtils.hasText(normalizedCode) && !StringUtils.hasText(normalizedReason)) {
            return null;
        }
        if (!StringUtils.hasText(normalizedCode)) {
            return normalizedReason;
        }
        if (!StringUtils.hasText(normalizedReason)) {
            return normalizedCode;
        }
        return normalizedCode + "|" + normalizedReason;
    }

    private java.time.Instant parseInstant(String raw, String fieldName) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return java.time.Instant.parse(raw.trim());
        } catch (java.time.format.DateTimeParseException ex) {
            throw new ApplicationException(
                    ErrorCode.VALIDATION_INVALID_INPUT,
                    fieldName + " must be an ISO-8601 instant"
            ).withDetail("field", fieldName)
                    .withDetail("value", raw);
        }
    }

    /* Promotions */
    @GetMapping("/sales/promotions")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SALES')")
    public ResponseEntity<ApiResponse<List<PromotionDto>>> promotions() {
        return ResponseEntity.ok(ApiResponse.success(salesService.listPromotions()));
    }

    @PostMapping("/sales/promotions")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<PromotionDto>> createPromotion(@Valid @RequestBody PromotionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Promotion created", salesService.createPromotion(request)));
    }

    @PutMapping("/sales/promotions/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<PromotionDto>> updatePromotion(@PathVariable Long id,
                                                                      @Valid @RequestBody PromotionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Promotion updated", salesService.updatePromotion(id, request)));
    }

    @DeleteMapping("/sales/promotions/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<Void> deletePromotion(@PathVariable Long id) {
        salesService.deletePromotion(id);
        return ResponseEntity.noContent().build();
    }

    /* Sales Targets */
    @GetMapping("/sales/targets")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SALES')")
    public ResponseEntity<ApiResponse<List<SalesTargetDto>>> targets() {
        return ResponseEntity.ok(ApiResponse.success(salesService.listTargets()));
    }

    @PostMapping("/sales/targets")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SalesTargetDto>> createTarget(@Valid @RequestBody SalesTargetRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Sales target created", salesService.createTarget(request)));
    }

    @PutMapping("/sales/targets/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SalesTargetDto>> updateTarget(@PathVariable Long id,
                                                                    @Valid @RequestBody SalesTargetRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Sales target updated", salesService.updateTarget(id, request)));
    }

    @DeleteMapping("/sales/targets/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Void> deleteTarget(@PathVariable Long id,
                                             @RequestParam(required = false) String reason) {
        salesService.deleteTarget(id, reason);
        return ResponseEntity.noContent().build();
    }

    /* Credit Requests */
    @GetMapping("/sales/credit-requests")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SALES')")
    public ResponseEntity<ApiResponse<List<CreditRequestDto>>> creditRequests() {
        return ResponseEntity.ok(ApiResponse.success(salesDealerCrudService.listCreditRequests()));
    }

    @PostMapping("/sales/credit-requests")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<CreditRequestDto>> createCreditRequest(@Valid @RequestBody CreditRequestRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Credit request created", salesDealerCrudService.createCreditRequest(request)));
    }

    @PutMapping("/sales/credit-requests/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<CreditRequestDto>> updateCreditRequest(@PathVariable Long id,
                                                                              @Valid @RequestBody CreditRequestRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Credit request updated", salesDealerCrudService.updateCreditRequest(id, request)));
    }

    @PostMapping("/sales/credit-requests/{id}/approve")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<CreditRequestDto>> approveCreditRequest(@PathVariable Long id,
                                                                              @Valid @RequestBody CreditRequestDecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Credit request approved",
                salesDealerCrudService.approveCreditRequest(id, request.reason())));
    }

    @PostMapping("/sales/credit-requests/{id}/reject")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<CreditRequestDto>> rejectCreditRequest(@PathVariable Long id,
                                                                             @Valid @RequestBody CreditRequestDecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Credit request rejected",
                salesDealerCrudService.rejectCreditRequest(id, request.reason())));
    }

    /* Dispatch confirmation (final invoice + AR at shipment) */
    @PostMapping("/sales/dispatch/confirm")
    @PreAuthorize(PortalRoleActionMatrix.FINANCIAL_DISPATCH)
    public ResponseEntity<ApiResponse<DispatchConfirmResponse>> confirmDispatch(@Valid @RequestBody DispatchConfirmRequest request) {
        if (DispatchMetadataValidator.shouldEnforceValidation(request, () -> isDispatchedSlipReplay(request.packingSlipId()))) {
            DispatchMetadataValidator.validate(request);
        }
        return ResponseEntity.ok(ApiResponse.success("Dispatch confirmed", salesDispatchReconciliationService.confirmDispatch(request)));
    }

    private boolean isDispatchedSlipReplay(Long packingSlipId) {
        if (packingSlipId == null) {
            return false;
        }
        try {
            PackagingSlipDto slip = finishedGoodsService.getPackagingSlip(packingSlipId);
            return slip != null && "DISPATCHED".equalsIgnoreCase(slip.status());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @PostMapping("/sales/dispatch/reconcile-order-markers")
    @PreAuthorize(PortalRoleActionMatrix.FINANCIAL_DISPATCH)
    public ResponseEntity<ApiResponse<DispatchMarkerReconciliationResponse>> reconcileOrderMarkers(
            @RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok(ApiResponse.success(
                "Order-level dispatch markers reconciled",
                salesDispatchReconciliationService.reconcileStaleOrderLevelMarkers(limit)));
    }
}
