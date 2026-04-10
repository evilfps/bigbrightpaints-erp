package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.CompanyDefaultAccountsRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.CompanyDefaultAccountsResponse;
import com.bigbrightpaints.erp.modules.accounting.service.AccountHierarchyService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

class AccountControllerTest {

  @Test
  void accounts_delegatesToAccountingService() {
    AccountingService accountingService = mock(AccountingService.class);
    List<AccountDto> expected =
        List.of(
            new AccountDto(
                1L, UUID.randomUUID(), "1000", "Cash", AccountType.ASSET, BigDecimal.TEN));
    when(accountingService.listAccounts()).thenReturn(expected);

    ApiResponse<List<AccountDto>> body =
        controller(accountingService, null, null).accounts().getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void defaultAccounts_mapsConfiguredDefaults() {
    CompanyDefaultAccountsService defaultAccountsService =
        mock(CompanyDefaultAccountsService.class);
    CompanyDefaultAccountsService.DefaultAccounts defaults =
        new CompanyDefaultAccountsService.DefaultAccounts(1L, 2L, 3L, 4L, 4L, 5L);
    when(defaultAccountsService.getDefaults()).thenReturn(defaults);

    ApiResponse<CompanyDefaultAccountsResponse> body =
        controller(null, defaultAccountsService, null).defaultAccounts().getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(new CompanyDefaultAccountsResponse(1L, 2L, 3L, 4L, 4L, 5L));
  }

  @Test
  void updateDefaultAccounts_delegatesToService() {
    CompanyDefaultAccountsService defaultAccountsService =
        mock(CompanyDefaultAccountsService.class);
    CompanyDefaultAccountsRequest request =
        new CompanyDefaultAccountsRequest(11L, 12L, 13L, 14L, 14L, 15L);
    CompanyDefaultAccountsService.DefaultAccounts updated =
        new CompanyDefaultAccountsService.DefaultAccounts(11L, 12L, 13L, 14L, 14L, 15L);
    when(defaultAccountsService.updateDefaults(11L, 12L, 13L, 14L, 14L, 15L)).thenReturn(updated);

    ApiResponse<CompanyDefaultAccountsResponse> body =
        controller(null, defaultAccountsService, null).updateDefaultAccounts(request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.message()).isEqualTo("Default accounts updated");
    assertThat(body.data())
        .isEqualTo(new CompanyDefaultAccountsResponse(11L, 12L, 13L, 14L, 14L, 15L));
  }

  @Test
  void createAccount_delegatesToAccountingService() {
    AccountingService accountingService = mock(AccountingService.class);
    AccountRequest request = new AccountRequest("4000", "Sales", AccountType.REVENUE, 7L);
    AccountDto expected =
        new AccountDto(
            44L, UUID.randomUUID(), "4000", "Sales", AccountType.REVENUE, BigDecimal.ZERO);
    when(accountingService.createAccount(request)).thenReturn(expected);

    ApiResponse<AccountDto> body =
        controller(accountingService, null, null).createAccount(request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.message()).isEqualTo("Account created");
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void getChartOfAccountsTree_delegatesToHierarchyService() {
    AccountHierarchyService hierarchyService = mock(AccountHierarchyService.class);
    List<AccountHierarchyService.AccountNode> expected =
        List.of(
            new AccountHierarchyService.AccountNode(
                1L, "1000", "Cash", "ASSET", BigDecimal.TEN, 0, null, List.of()));
    when(hierarchyService.getChartOfAccountsTree()).thenReturn(expected);

    ApiResponse<List<AccountHierarchyService.AccountNode>> body =
        controller(null, null, hierarchyService).getChartOfAccountsTree().getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void getAccountTreeByType_normalizesTypeBeforeLookup() {
    AccountHierarchyService hierarchyService = mock(AccountHierarchyService.class);
    List<AccountHierarchyService.AccountNode> expected = List.of();
    when(hierarchyService.getTreeByType(AccountType.LIABILITY)).thenReturn(expected);

    ApiResponse<List<AccountHierarchyService.AccountNode>> body =
        controller(null, null, hierarchyService).getAccountTreeByType("liability").getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isSameAs(expected);
    verify(hierarchyService).getTreeByType(AccountType.LIABILITY);
  }

  @Test
  void getAccountTreeByType_rejectsUnknownType() {
    assertThatThrownBy(() -> controller(null, null, null).getAccountTreeByType("mystery"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException applicationException = (ApplicationException) ex;
              assertThat(applicationException.getErrorCode())
                  .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
              assertThat(applicationException.getUserMessage())
                  .isEqualTo("Invalid account type 'mystery'");
              assertThat(applicationException.getDetails()).containsEntry("type", "mystery");
            });
  }

  @Test
  void getAccountTreeByType_rejectsBlankType() {
    assertThatThrownBy(() -> controller(null, null, null).getAccountTreeByType("   "))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException applicationException = (ApplicationException) ex;
              assertThat(applicationException.getErrorCode())
                  .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
              assertThat(applicationException.getUserMessage()).isEqualTo("Account type is required");
              assertThat(applicationException.getDetails()).containsEntry("type", "   ");
            });
  }

  private AccountController controller(
      AccountingService accountingService,
      CompanyDefaultAccountsService companyDefaultAccountsService,
      AccountHierarchyService accountHierarchyService) {
    return new AccountController(
        accountingService != null ? accountingService : mock(AccountingService.class),
        companyDefaultAccountsService != null
            ? companyDefaultAccountsService
            : mock(CompanyDefaultAccountsService.class),
        accountHierarchyService != null
            ? accountHierarchyService
            : mock(AccountHierarchyService.class));
  }
}
