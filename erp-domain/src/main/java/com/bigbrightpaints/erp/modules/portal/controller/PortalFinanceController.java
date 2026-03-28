package com.bigbrightpaints.erp.modules.portal.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.modules.sales.service.DealerPortalService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/portal/finance")
@PreAuthorize(PortalRoleActionMatrix.ADMIN_OR_ACCOUNTING)
public class PortalFinanceController {

  private final DealerPortalService dealerPortalService;

  public PortalFinanceController(DealerPortalService dealerPortalService) {
    this.dealerPortalService = dealerPortalService;
  }

  @GetMapping("/ledger")
  public ResponseEntity<ApiResponse<Map<String, Object>>> ledger(@RequestParam Long dealerId) {
    return ResponseEntity.ok(
        ApiResponse.success("Dealer ledger", dealerPortalService.getLedgerForDealer(dealerId)));
  }

  @GetMapping("/invoices")
  public ResponseEntity<ApiResponse<Map<String, Object>>> invoices(@RequestParam Long dealerId) {
    return ResponseEntity.ok(
        ApiResponse.success("Dealer invoices", dealerPortalService.getInvoicesForDealer(dealerId)));
  }

  @GetMapping("/aging")
  public ResponseEntity<ApiResponse<Map<String, Object>>> aging(@RequestParam Long dealerId) {
    return ResponseEntity.ok(
        ApiResponse.success("Dealer aging", dealerPortalService.getAgingForDealer(dealerId)));
  }
}
