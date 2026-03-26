package com.bigbrightpaints.erp.modules.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.modules.admin.dto.ChangelogEntryResponse;
import com.bigbrightpaints.erp.modules.admin.service.ChangelogService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@RestController
@RequestMapping("/api/v1/changelog")
@PreAuthorize("isAuthenticated()")
public class ChangelogController {

  private final ChangelogService changelogService;

  public ChangelogController(ChangelogService changelogService) {
    this.changelogService = changelogService;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<PageResponse<ChangelogEntryResponse>>> list(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok(ApiResponse.success(changelogService.list(page, size)));
  }

  @GetMapping("/latest-highlighted")
  public ResponseEntity<ApiResponse<ChangelogEntryResponse>> latestHighlighted() {
    return ResponseEntity.ok(ApiResponse.success(changelogService.latestHighlighted()));
  }
}
