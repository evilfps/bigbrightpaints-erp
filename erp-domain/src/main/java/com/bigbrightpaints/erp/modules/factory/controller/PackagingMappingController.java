package com.bigbrightpaints.erp.modules.factory.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.bigbrightpaints.erp.modules.factory.dto.PackagingSizeMappingDto;
import com.bigbrightpaints.erp.modules.factory.dto.PackagingSizeMappingRequest;
import com.bigbrightpaints.erp.modules.factory.service.PackagingMaterialService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import jakarta.validation.Valid;

/** Controller for managing Packaging Setup / Rules for automatic packing consumption. */
@RestController
@RequestMapping("/api/v1/factory/packaging-mappings")
public class PackagingMappingController {

  private final PackagingMaterialService packagingMaterialService;

  public PackagingMappingController(PackagingMaterialService packagingMaterialService) {
    this.packagingMaterialService = packagingMaterialService;
  }

  @GetMapping
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')")
  public ResponseEntity<ApiResponse<List<PackagingSizeMappingDto>>> listMappings() {
    List<PackagingSizeMappingDto> mappings = packagingMaterialService.listMappings();
    return ResponseEntity.ok(ApiResponse.success("Packaging setup rules", mappings));
  }

  @GetMapping("/active")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')")
  public ResponseEntity<ApiResponse<List<PackagingSizeMappingDto>>> listActiveMappings() {
    List<PackagingSizeMappingDto> mappings = packagingMaterialService.listActiveMappings();
    return ResponseEntity.ok(ApiResponse.success("Active packaging setup rules", mappings));
  }

  @PostMapping
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN')")
  public ResponseEntity<ApiResponse<PackagingSizeMappingDto>> createMapping(
      @Valid @RequestBody PackagingSizeMappingRequest request) {
    PackagingSizeMappingDto created = packagingMaterialService.createMapping(request);
    return ResponseEntity.ok(ApiResponse.success("Packaging setup rule created", created));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN')")
  public ResponseEntity<ApiResponse<PackagingSizeMappingDto>> updateMapping(
      @PathVariable Long id, @Valid @RequestBody PackagingSizeMappingRequest request) {
    PackagingSizeMappingDto updated = packagingMaterialService.updateMapping(id, request);
    return ResponseEntity.ok(ApiResponse.success("Packaging setup rule updated", updated));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN')")
  public ResponseEntity<ApiResponse<Void>> deactivateMapping(@PathVariable Long id) {
    packagingMaterialService.deactivateMapping(id);
    return ResponseEntity.ok(ApiResponse.success("Packaging setup rule deactivated", null));
  }
}
