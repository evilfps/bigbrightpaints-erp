package com.bigbrightpaints.erp.modules.sales.controller;

import com.bigbrightpaints.erp.modules.sales.dto.*;
import com.bigbrightpaints.erp.modules.sales.service.DealerService;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class SalesController {

    private final SalesService salesService;
    private final DealerService dealerService;

    public SalesController(SalesService salesService, DealerService dealerService) {
        this.salesService = salesService;
        this.dealerService = dealerService;
    }

    /* Dealers alias - frontend calls /sales/dealers, backend has /dealers */
    @GetMapping("/sales/dealers")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SALES')")
    public ResponseEntity<ApiResponse<List<DealerResponse>>> listDealers() {
        return ResponseEntity.ok(ApiResponse.success("Dealer directory", dealerService.listDealers()));
    }

    @GetMapping("/sales/dealers/search")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SALES')")
    public ResponseEntity<ApiResponse<List<DealerLookupResponse>>> searchDealers(@RequestParam(defaultValue = "") String query) {
        return ResponseEntity.ok(ApiResponse.success(dealerService.search(query)));
    }

    /* Sales Orders */
    @GetMapping("/sales/orders")
    @Timed(value = "erp.sales.orders.list", description = "List sales orders")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SALES','ROLE_FACTORY')")
    public ResponseEntity<ApiResponse<List<SalesOrderDto>>> orders(@RequestParam(required = false) String status,
                                                                   @RequestParam(defaultValue = "0") int page,
                                                                   @RequestParam(defaultValue = "100") int size) {
        return ResponseEntity.ok(ApiResponse.success(salesService.listOrders(status, page, size)));
    }

    @PostMapping("/sales/orders")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SalesOrderDto>> createOrder(@Valid @RequestBody SalesOrderRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Order created", salesService.createOrder(request)));
    }

    @PutMapping("/sales/orders/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SalesOrderDto>> updateOrder(@PathVariable Long id,
                                                                   @Valid @RequestBody SalesOrderRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Order updated", salesService.updateOrder(id, request)));
    }

    @DeleteMapping("/sales/orders/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<Void> deleteOrder(@PathVariable Long id) {
        salesService.deleteOrder(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sales/orders/{id}/confirm")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SalesOrderDto>> confirmOrder(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Order confirmed", salesService.confirmOrder(id)));
    }

    @PostMapping("/sales/orders/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SalesOrderDto>> cancelOrder(@PathVariable Long id,
                                                                   @RequestBody(required = false) CancelRequest request) {
        String reason = request == null ? null : request.reason();
        return ResponseEntity.ok(ApiResponse.success("Order cancelled", salesService.cancelOrder(id, reason)));
    }

    @PatchMapping("/sales/orders/{id}/status")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SalesOrderDto>> updateStatus(@PathVariable Long id,
                                                                    @RequestBody StatusRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Status updated", salesService.updateStatus(id, request.status())));
    }

    public record CancelRequest(String reason) {}
    public record StatusRequest(String status) {}

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
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SalesTargetDto>> createTarget(@Valid @RequestBody SalesTargetRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Sales target created", salesService.createTarget(request)));
    }

    @PutMapping("/sales/targets/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SalesTargetDto>> updateTarget(@PathVariable Long id,
                                                                    @Valid @RequestBody SalesTargetRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Sales target updated", salesService.updateTarget(id, request)));
    }

    @DeleteMapping("/sales/targets/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<Void> deleteTarget(@PathVariable Long id) {
        salesService.deleteTarget(id);
        return ResponseEntity.noContent().build();
    }

    /* Credit Requests */
    @GetMapping("/sales/credit-requests")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SALES')")
    public ResponseEntity<ApiResponse<List<CreditRequestDto>>> creditRequests() {
        return ResponseEntity.ok(ApiResponse.success(salesService.listCreditRequests()));
    }

    @PostMapping("/sales/credit-requests")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<CreditRequestDto>> createCreditRequest(@Valid @RequestBody CreditRequestRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Credit request created", salesService.createCreditRequest(request)));
    }

    @PutMapping("/sales/credit-requests/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<CreditRequestDto>> updateCreditRequest(@PathVariable Long id,
                                                                              @Valid @RequestBody CreditRequestRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Credit request updated", salesService.updateCreditRequest(id, request)));
    }

    /* Dispatch confirmation (final invoice + AR at shipment) */
    @PostMapping("/sales/dispatch/confirm")
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ACCOUNTING','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<DispatchConfirmResponse>> confirmDispatch(@Valid @RequestBody DispatchConfirmRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Dispatch confirmed", salesService.confirmDispatch(request)));
    }
}
