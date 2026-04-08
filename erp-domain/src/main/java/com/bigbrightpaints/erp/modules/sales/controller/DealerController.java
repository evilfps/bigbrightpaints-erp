package com.bigbrightpaints.erp.modules.sales.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.modules.sales.dto.CreateDealerRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DealerDunningHoldResponse;
import com.bigbrightpaints.erp.modules.sales.dto.DealerImportResponse;
import com.bigbrightpaints.erp.modules.sales.dto.DealerLookupResponse;
import com.bigbrightpaints.erp.modules.sales.dto.DealerResponse;
import com.bigbrightpaints.erp.modules.sales.service.DealerImportService;
import com.bigbrightpaints.erp.modules.sales.service.DealerService;
import com.bigbrightpaints.erp.modules.sales.service.DunningService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/dealers")
public class DealerController {

  private final DealerService dealerService;
  private final DealerImportService dealerImportService;
  private final DunningService dunningService;

  public DealerController(
      DealerService dealerService,
      DealerImportService dealerImportService,
      DunningService dunningService) {
    this.dealerService = dealerService;
    this.dealerImportService = dealerImportService;
    this.dunningService = dunningService;
  }

  @PostMapping
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_SALES_ACCOUNTING)
  @Operation(summary = "Create dealer")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "Dealer created"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "Validation failed")
  })
  public ResponseEntity<ApiResponse<DealerResponse>> createDealer(
      @Valid @RequestBody CreateDealerRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.success("Dealer created", dealerService.createDealer(request)));
  }

  @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_SALES_ACCOUNTING)
  public ResponseEntity<ApiResponse<DealerImportResponse>> importDealers(
      @RequestPart("file") MultipartFile file) {
    return ResponseEntity.ok(
        ApiResponse.success("Dealer import processed", dealerImportService.importDealers(file)));
  }

  @GetMapping
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_SALES_ACCOUNTING)
  public ResponseEntity<ApiResponse<List<DealerResponse>>> listDealers(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return ResponseEntity.ok(
        ApiResponse.success("Dealer directory", dealerService.listDealers(status, page, size)));
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
  @Operation(
      summary = "Place dealer on dunning hold",
      description =
          "Explicitly places the dealer into ON_HOLD status and reports whether the hold was newly"
              + " applied.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Dealer is now on hold or was already on hold"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "Dealer not found")
  })
  public ResponseEntity<ApiResponse<DealerDunningHoldResponse>> placeDunningHold(
      @PathVariable Long dealerId) {
    boolean placed = dunningService.placeDealerOnHold(dealerId);
    return ResponseEntity.ok(
        ApiResponse.success(
            "Dealer placed on dunning hold",
            new DealerDunningHoldResponse(dealerId, true, "ON_HOLD", !placed)));
  }
}
