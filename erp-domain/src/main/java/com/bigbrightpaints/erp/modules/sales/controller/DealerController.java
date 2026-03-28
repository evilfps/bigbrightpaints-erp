package com.bigbrightpaints.erp.modules.sales.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.modules.sales.dto.CreateDealerRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DealerLookupResponse;
import com.bigbrightpaints.erp.modules.sales.dto.DealerResponse;
import com.bigbrightpaints.erp.modules.sales.service.DealerService;
import com.bigbrightpaints.erp.modules.sales.service.DunningService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/dealers")
public class DealerController {

  private final DealerService dealerService;
  private final DunningService dunningService;

  public DealerController(DealerService dealerService, DunningService dunningService) {
    this.dealerService = dealerService;
    this.dunningService = dunningService;
  }

  @PostMapping
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_SALES_ACCOUNTING)
  public ResponseEntity<ApiResponse<DealerResponse>> createDealer(
      @Valid @RequestBody CreateDealerRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success("Dealer created", dealerService.createDealer(request)));
  }

  @GetMapping
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_SALES_ACCOUNTING)
  public ResponseEntity<ApiResponse<List<DealerResponse>>> listDealers() {
    return ResponseEntity.ok(ApiResponse.success("Dealer directory", dealerService.listDealers()));
  }

  @GetMapping("/search")
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_SALES_ACCOUNTING)
  public ResponseEntity<ApiResponse<List<DealerLookupResponse>>> searchDealers(
      @RequestParam(defaultValue = "") String query,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String region,
      @RequestParam(required = false) String creditStatus) {
    return ResponseEntity.ok(
        ApiResponse.success(dealerService.search(query, status, region, creditStatus)));
  }

  @PutMapping("/{dealerId}")
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_SALES_ACCOUNTING)
  public ResponseEntity<ApiResponse<DealerResponse>> updateDealer(
      @PathVariable Long dealerId, @Valid @RequestBody CreateDealerRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success("Dealer updated", dealerService.updateDealer(dealerId, request)));
  }

  @PostMapping("/{dealerId}/dunning/hold")
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_SALES_ACCOUNTING)
  public ResponseEntity<ApiResponse<Map<String, Object>>> holdIfOverdue(
      @PathVariable Long dealerId,
      @RequestParam(defaultValue = "45") int overdueDays,
      @RequestParam(defaultValue = "0") BigDecimal minAmount) {
    boolean placed = dunningService.evaluateDealerHold(dealerId, overdueDays, minAmount);
    return ResponseEntity.ok(
        ApiResponse.success(
            "Dunning evaluated",
            Map.of(
                "dealerId", dealerId,
                "placedOnHold", placed)));
  }
}
