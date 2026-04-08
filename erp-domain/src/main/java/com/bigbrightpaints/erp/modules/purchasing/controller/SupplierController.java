package com.bigbrightpaints.erp.modules.purchasing.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.bigbrightpaints.erp.modules.purchasing.dto.SupplierImportResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.SupplierRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.SupplierResponse;
import com.bigbrightpaints.erp.modules.purchasing.service.SupplierImportService;
import com.bigbrightpaints.erp.modules.purchasing.service.SupplierService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/suppliers")
public class SupplierController {

  private final SupplierService supplierService;
  private final SupplierImportService supplierImportService;

  public SupplierController(
      SupplierService supplierService, SupplierImportService supplierImportService) {
    this.supplierService = supplierService;
    this.supplierImportService = supplierImportService;
  }

  @GetMapping
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_FACTORY')")
  public ResponseEntity<ApiResponse<List<SupplierResponse>>> listSuppliers(
      Authentication authentication) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Suppliers",
            supplierService.listSuppliers(canViewSensitiveBankDetails(authentication))));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_FACTORY')")
  public ResponseEntity<ApiResponse<SupplierResponse>> getSupplier(
      @PathVariable Long id, Authentication authentication) {
    return ResponseEntity.ok(
        ApiResponse.success(
            supplierService.getSupplier(id, canViewSensitiveBankDetails(authentication))));
  }

  @PostMapping
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<ApiResponse<SupplierResponse>> createSupplier(
      @Valid @RequestBody SupplierRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.success("Supplier created", supplierService.createSupplier(request)));
  }

  @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<SupplierImportResponse>> importSuppliers(
      @RequestPart("file") MultipartFile file) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Supplier import processed", supplierImportService.importSuppliers(file)));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<SupplierResponse>> updateSupplier(
      @PathVariable Long id, @Valid @RequestBody SupplierRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success("Supplier updated", supplierService.updateSupplier(id, request)));
  }

  @PostMapping("/{id}/approve")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<SupplierResponse>> approveSupplier(@PathVariable Long id) {
    return ResponseEntity.ok(
        ApiResponse.success("Supplier approved", supplierService.approveSupplier(id)));
  }

  @PostMapping("/{id}/activate")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<SupplierResponse>> activateSupplier(@PathVariable Long id) {
    return ResponseEntity.ok(
        ApiResponse.success("Supplier activated", supplierService.activateSupplier(id)));
  }

  @PostMapping("/{id}/suspend")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<SupplierResponse>> suspendSupplier(@PathVariable Long id) {
    return ResponseEntity.ok(
        ApiResponse.success("Supplier suspended", supplierService.suspendSupplier(id)));
  }

  private boolean canViewSensitiveBankDetails(Authentication authentication) {
    if (authentication == null || authentication.getAuthorities() == null) {
      return false;
    }
    return authentication.getAuthorities().stream()
        .map(grantedAuthority -> grantedAuthority.getAuthority())
        .anyMatch(
            authority -> "ROLE_ADMIN".equals(authority) || "ROLE_ACCOUNTING".equals(authority));
  }
}
