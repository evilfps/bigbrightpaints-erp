package com.bigbrightpaints.erp.modules.accounting.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountRequest;

/**
 * Canonical owner for chart-of-accounts and default-account API resolution.
 *
 * <p>Centralizes account creation/listing, hierarchy reads, and default-account read/update flows
 * behind one explicit accounting owner.
 */
@Service
public class AccountResolutionOwnerService {

  private final AccountCatalogService accountCatalogService;
  private final AccountHierarchyService accountHierarchyService;
  private final CompanyDefaultAccountsService companyDefaultAccountsService;

  public AccountResolutionOwnerService(
      AccountCatalogService accountCatalogService,
      AccountHierarchyService accountHierarchyService,
      CompanyDefaultAccountsService companyDefaultAccountsService) {
    this.accountCatalogService = accountCatalogService;
    this.accountHierarchyService = accountHierarchyService;
    this.companyDefaultAccountsService = companyDefaultAccountsService;
  }

  public List<AccountDto> listAccounts() {
    return accountCatalogService.listAccounts();
  }

  public AccountDto createAccount(AccountRequest request) {
    return accountCatalogService.createAccount(request);
  }

  public List<AccountHierarchyService.AccountNode> getChartOfAccountsTree() {
    return accountHierarchyService.getChartOfAccountsTree();
  }

  public List<AccountHierarchyService.AccountNode> getTreeByType(AccountType type) {
    return accountHierarchyService.getTreeByType(type);
  }

  public CompanyDefaultAccountsService.DefaultAccounts getDefaultAccounts() {
    return companyDefaultAccountsService.getDefaults();
  }

  public CompanyDefaultAccountsService.DefaultAccounts updateDefaultAccounts(
      Long inventoryAccountId,
      Long cogsAccountId,
      Long revenueAccountId,
      Long discountAccountId,
      Long fgDiscountAccountId,
      Long taxAccountId,
      List<String> clearAccountFields) {
    return companyDefaultAccountsService.updateDefaults(
        inventoryAccountId,
        cogsAccountId,
        revenueAccountId,
        discountAccountId,
        fgDiscountAccountId,
        taxAccountId,
        clearAccountFields);
  }
}
