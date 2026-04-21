package com.bigbrightpaints.erp.modules.accounting.controller;

import java.util.Arrays;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.CompanyDefaultAccountsRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.CompanyDefaultAccountsResponse;
import com.bigbrightpaints.erp.modules.accounting.service.AccountHierarchyService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountResolutionOwnerService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/accounting")
public class AccountController {

  private final AccountResolutionOwnerService accountResolutionOwnerService;

  public AccountController(AccountResolutionOwnerService accountResolutionOwnerService) {
    this.accountResolutionOwnerService = accountResolutionOwnerService;
  }

  @GetMapping("/accounts")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<List<AccountDto>>> accounts() {
    return ResponseEntity.ok(ApiResponse.success(accountResolutionOwnerService.listAccounts()));
  }

  @GetMapping("/default-accounts")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<CompanyDefaultAccountsResponse>> defaultAccounts() {
    return ResponseEntity.ok(
        ApiResponse.success(toResponse(accountResolutionOwnerService.getDefaultAccounts())));
  }

  @PutMapping("/default-accounts")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<CompanyDefaultAccountsResponse>> updateDefaultAccounts(
      @Valid @RequestBody CompanyDefaultAccountsRequest request) {
    CompanyDefaultAccountsService.DefaultAccounts defaults =
        accountResolutionOwnerService.updateDefaultAccounts(
            request.inventoryAccountId(),
            request.cogsAccountId(),
            request.revenueAccountId(),
            request.discountAccountId(),
            request.fgDiscountAccountId(),
            request.taxAccountId());
    return ResponseEntity.ok(ApiResponse.success("Default accounts updated", toResponse(defaults)));
  }

  @PostMapping("/accounts")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<AccountDto>> createAccount(
      @Valid @RequestBody AccountRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Account created", accountResolutionOwnerService.createAccount(request)));
  }

  @GetMapping("/accounts/tree")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<List<AccountHierarchyService.AccountNode>>>
      getChartOfAccountsTree() {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Chart of accounts hierarchy", accountResolutionOwnerService.getChartOfAccountsTree()));
  }

  @GetMapping("/accounts/tree/{type}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<List<AccountHierarchyService.AccountNode>>>
      getAccountTreeByType(@PathVariable String type) {
    AccountType accountType = parseAccountType(type);
    return ResponseEntity.ok(
        ApiResponse.success(
            "Account hierarchy for " + type,
            accountResolutionOwnerService.getTreeByType(accountType)));
  }

  private AccountType parseAccountType(String type) {
    if (type == null || type.isBlank()) {
      throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Account type is required")
          .withDetail("type", type);
    }
    try {
      return AccountType.valueOf(type.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT, "Invalid account type '" + type.trim() + "'")
          .withDetail("type", type.trim())
          .withDetail(
              "allowedValues", Arrays.stream(AccountType.values()).map(Enum::name).toList());
    }
  }

  private CompanyDefaultAccountsResponse toResponse(
      CompanyDefaultAccountsService.DefaultAccounts defaults) {
    return new CompanyDefaultAccountsResponse(
        defaults.inventoryAccountId(),
        defaults.cogsAccountId(),
        defaults.revenueAccountId(),
        defaults.discountAccountId(),
        defaults.fgDiscountAccountId(),
        defaults.taxAccountId());
  }
}
