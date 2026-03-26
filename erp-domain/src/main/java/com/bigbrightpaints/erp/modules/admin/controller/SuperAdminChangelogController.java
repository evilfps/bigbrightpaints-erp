package com.bigbrightpaints.erp.modules.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.modules.admin.dto.ChangelogEntryRequest;
import com.bigbrightpaints.erp.modules.admin.dto.ChangelogEntryResponse;
import com.bigbrightpaints.erp.modules.admin.service.ChangelogService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/superadmin/changelog")
@PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
public class SuperAdminChangelogController {

  private final ChangelogService changelogService;

  public SuperAdminChangelogController(ChangelogService changelogService) {
    this.changelogService = changelogService;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<ChangelogEntryResponse>> create(
      @Valid @RequestBody ChangelogEntryRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success("Changelog entry created", changelogService.create(request)));
  }

  @PutMapping("/{id}")
  public ResponseEntity<ApiResponse<ChangelogEntryResponse>> update(
      @PathVariable Long id, @Valid @RequestBody ChangelogEntryRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success("Changelog entry updated", changelogService.update(id, request)));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Delete a changelog entry")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "204",
      description = "No Content")
  public ResponseEntity<Void> delete(@PathVariable Long id) {
    changelogService.softDelete(id);
    return ResponseEntity.noContent().build();
  }
}
