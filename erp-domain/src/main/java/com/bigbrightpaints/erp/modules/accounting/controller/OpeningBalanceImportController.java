package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.modules.accounting.dto.OpeningBalanceImportResponse;
import com.bigbrightpaints.erp.modules.accounting.service.OpeningBalanceImportService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/accounting")
public class OpeningBalanceImportController {

    private final OpeningBalanceImportService openingBalanceImportService;

    public OpeningBalanceImportController(OpeningBalanceImportService openingBalanceImportService) {
        this.openingBalanceImportService = openingBalanceImportService;
    }

    @PostMapping(value = "/opening-balances", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<OpeningBalanceImportResponse>> importOpeningBalances(
            @RequestPart("file") MultipartFile file) {
        OpeningBalanceImportResponse response = openingBalanceImportService.importOpeningBalances(file);
        return ResponseEntity.ok(ApiResponse.success("Opening balances import processed", response));
    }
}
