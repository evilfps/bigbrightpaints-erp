package com.bigbrightpaints.erp.modules.sales.controller;

import com.bigbrightpaints.erp.modules.sales.dto.*;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class SalesController {

    private final SalesService salesService;

    public SalesController(SalesService salesService) {
        this.salesService = salesService;
    }

    /* Dealers */
    @GetMapping("/dealers")
    public ResponseEntity<ApiResponse<List<DealerDto>>> dealers() {
        return ResponseEntity.ok(ApiResponse.success(salesService.listDealers()));
    }

    @PostMapping("/dealers")
    public ResponseEntity<ApiResponse<DealerDto>> createDealer(@Valid @RequestBody DealerRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Dealer created", salesService.createDealer(request)));
    }

    @PutMapping("/dealers/{id}")
    public ResponseEntity<ApiResponse<DealerDto>> updateDealer(@PathVariable Long id, @Valid @RequestBody DealerRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Dealer updated", salesService.updateDealer(id, request)));
    }

    @DeleteMapping("/dealers/{id}")
    public ResponseEntity<Void> deleteDealer(@PathVariable Long id) {
        salesService.deleteDealer(id);
        return ResponseEntity.noContent().build();
    }

    /* Sales Orders */
    @GetMapping("/sales/orders")
    public ResponseEntity<ApiResponse<List<SalesOrderDto>>> orders(@RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiResponse.success(salesService.listOrders(status)));
    }

    @PostMapping("/sales/orders")
    public ResponseEntity<ApiResponse<SalesOrderDto>> createOrder(@Valid @RequestBody SalesOrderRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Order created", salesService.createOrder(request)));
    }

    @PutMapping("/sales/orders/{id}")
    public ResponseEntity<ApiResponse<SalesOrderDto>> updateOrder(@PathVariable Long id,
                                                                   @Valid @RequestBody SalesOrderRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Order updated", salesService.updateOrder(id, request)));
    }

    @DeleteMapping("/sales/orders/{id}")
    public ResponseEntity<Void> deleteOrder(@PathVariable Long id) {
        salesService.deleteOrder(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sales/orders/{id}/confirm")
    public ResponseEntity<ApiResponse<SalesOrderDto>> confirmOrder(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Order confirmed", salesService.confirmOrder(id)));
    }

    @PostMapping("/sales/orders/{id}/cancel")
    public ResponseEntity<ApiResponse<SalesOrderDto>> cancelOrder(@PathVariable Long id,
                                                                   @RequestBody(required = false) CancelRequest request) {
        String reason = request == null ? null : request.reason();
        return ResponseEntity.ok(ApiResponse.success("Order cancelled", salesService.cancelOrder(id, reason)));
    }

    @PatchMapping("/sales/orders/{id}/status")
    public ResponseEntity<ApiResponse<SalesOrderDto>> updateStatus(@PathVariable Long id,
                                                                    @RequestBody StatusRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Status updated", salesService.updateStatus(id, request.status())));
    }

    public record CancelRequest(String reason) {}
    public record StatusRequest(String status) {}

    /* Promotions */
    @GetMapping("/sales/promotions")
    public ResponseEntity<ApiResponse<List<PromotionDto>>> promotions() {
        return ResponseEntity.ok(ApiResponse.success(salesService.listPromotions()));
    }

    @PostMapping("/sales/promotions")
    public ResponseEntity<ApiResponse<PromotionDto>> createPromotion(@Valid @RequestBody PromotionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Promotion created", salesService.createPromotion(request)));
    }

    @PutMapping("/sales/promotions/{id}")
    public ResponseEntity<ApiResponse<PromotionDto>> updatePromotion(@PathVariable Long id,
                                                                      @Valid @RequestBody PromotionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Promotion updated", salesService.updatePromotion(id, request)));
    }

    @DeleteMapping("/sales/promotions/{id}")
    public ResponseEntity<Void> deletePromotion(@PathVariable Long id) {
        salesService.deletePromotion(id);
        return ResponseEntity.noContent().build();
    }

    /* Sales Targets */
    @GetMapping("/sales/targets")
    public ResponseEntity<ApiResponse<List<SalesTargetDto>>> targets() {
        return ResponseEntity.ok(ApiResponse.success(salesService.listTargets()));
    }

    @PostMapping("/sales/targets")
    public ResponseEntity<ApiResponse<SalesTargetDto>> createTarget(@Valid @RequestBody SalesTargetRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Sales target created", salesService.createTarget(request)));
    }

    @PutMapping("/sales/targets/{id}")
    public ResponseEntity<ApiResponse<SalesTargetDto>> updateTarget(@PathVariable Long id,
                                                                    @Valid @RequestBody SalesTargetRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Sales target updated", salesService.updateTarget(id, request)));
    }

    @DeleteMapping("/sales/targets/{id}")
    public ResponseEntity<Void> deleteTarget(@PathVariable Long id) {
        salesService.deleteTarget(id);
        return ResponseEntity.noContent().build();
    }

    /* Credit Requests */
    @GetMapping("/sales/credit-requests")
    public ResponseEntity<ApiResponse<List<CreditRequestDto>>> creditRequests() {
        return ResponseEntity.ok(ApiResponse.success(salesService.listCreditRequests()));
    }

    @PostMapping("/sales/credit-requests")
    public ResponseEntity<ApiResponse<CreditRequestDto>> createCreditRequest(@Valid @RequestBody CreditRequestRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Credit request created", salesService.createCreditRequest(request)));
    }

    @PutMapping("/sales/credit-requests/{id}")
    public ResponseEntity<ApiResponse<CreditRequestDto>> updateCreditRequest(@PathVariable Long id,
                                                                              @Valid @RequestBody CreditRequestRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Credit request updated", salesService.updateCreditRequest(id, request)));
    }
}
