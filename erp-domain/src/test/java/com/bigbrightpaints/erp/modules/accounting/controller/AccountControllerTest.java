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
import com.bigbrightpaints.erp.modules.accounting.service.AccountResolutionOwnerService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

class AccountControllerTest {

  @Test
  void accounts_delegatesToAccountingService() {
    AccountResolutionOwnerService accountResolutionOwnerService =
        mock(AccountResolutionOwnerService.class);
    List<AccountDto> expected =
        List.of(
            new AccountDto(
                1L, UUID.randomUUID(), "1000", "Cash", AccountType.ASSET, BigDecimal.TEN));
    when(accountResolutionOwnerService.listAccounts()).thenReturn(expected);

    ApiResponse<List<AccountDto>> body =
        controller(accountResolutionOwnerService).accounts().getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void defaultAccounts_mapsConfiguredDefaults() {
    AccountResolutionOwnerService accountResolutionOwnerService =
        mock(AccountResolutionOwnerService.class);
    CompanyDefaultAccountsService.DefaultAccounts defaults =
        new CompanyDefaultAccountsService.DefaultAccounts(1L, 2L, 3L, 4L, 4L, 5L);
    when(accountResolutionOwnerService.getDefaultAccounts()).thenReturn(defaults);

    ApiResponse<CompanyDefaultAccountsResponse> body =
        controller(accountResolutionOwnerService).defaultAccounts().getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(new CompanyDefaultAccountsResponse(1L, 2L, 3L, 4L, 4L, 5L));
  }

  @Test
  void updateDefaultAccounts_delegatesToService() {
    AccountResolutionOwnerService accountResolutionOwnerService =
        mock(AccountResolutionOwnerService.class);
    CompanyDefaultAccountsRequest request =
        new CompanyDefaultAccountsRequest(
            11L, 12L, 13L, 14L, 14L, 15L, List.of("revenueAccountId"));
    CompanyDefaultAccountsService.DefaultAccounts updated =
        new CompanyDefaultAccountsService.DefaultAccounts(11L, 12L, 13L, 14L, 14L, 15L);
    when(accountResolutionOwnerService.updateDefaultAccounts(
            11L, 12L, 13L, 14L, 14L, 15L, List.of("revenueAccountId")))
        .thenReturn(updated);

    ApiResponse<CompanyDefaultAccountsResponse> body =
        controller(accountResolutionOwnerService).updateDefaultAccounts(request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.message()).isEqualTo("Default accounts updated");
    assertThat(body.data())
        .isEqualTo(new CompanyDefaultAccountsResponse(11L, 12L, 13L, 14L, 14L, 15L));
    verify(accountResolutionOwnerService)
        .updateDefaultAccounts(11L, 12L, 13L, 14L, 14L, 15L, List.of("revenueAccountId"));
  }

  @Test
  void createAccount_delegatesToAccountingService() {
    AccountResolutionOwnerService accountResolutionOwnerService =
        mock(AccountResolutionOwnerService.class);
    AccountRequest request = new AccountRequest("4000", "Sales", AccountType.REVENUE, 7L);
    AccountDto expected =
        new AccountDto(
            44L, UUID.randomUUID(), "4000", "Sales", AccountType.REVENUE, BigDecimal.ZERO);
    when(accountResolutionOwnerService.createAccount(request)).thenReturn(expected);

    ApiResponse<AccountDto> body =
        controller(accountResolutionOwnerService).createAccount(request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.message()).isEqualTo("Account created");
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void getChartOfAccountsTree_delegatesToHierarchyService() {
    AccountResolutionOwnerService accountResolutionOwnerService =
        mock(AccountResolutionOwnerService.class);
    List<AccountHierarchyService.AccountNode> expected =
        List.of(
            new AccountHierarchyService.AccountNode(
                1L, "1000", "Cash", "ASSET", BigDecimal.TEN, 0, null, List.of()));
    when(accountResolutionOwnerService.getChartOfAccountsTree()).thenReturn(expected);

    ApiResponse<List<AccountHierarchyService.AccountNode>> body =
        controller(accountResolutionOwnerService).getChartOfAccountsTree().getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void getAccountTreeByType_normalizesTypeBeforeLookup() {
    AccountResolutionOwnerService accountResolutionOwnerService =
        mock(AccountResolutionOwnerService.class);
    List<AccountHierarchyService.AccountNode> expected = List.of();
    when(accountResolutionOwnerService.getTreeByType(AccountType.LIABILITY)).thenReturn(expected);

    ApiResponse<List<AccountHierarchyService.AccountNode>> body =
        controller(accountResolutionOwnerService).getAccountTreeByType("liability").getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isSameAs(expected);
    verify(accountResolutionOwnerService).getTreeByType(AccountType.LIABILITY);
  }

  @Test
  void getAccountTreeByType_rejectsUnknownType() {
    assertThatThrownBy(() -> controller(null).getAccountTreeByType("mystery"))
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
    assertThatThrownBy(() -> controller(null).getAccountTreeByType("   "))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException applicationException = (ApplicationException) ex;
              assertThat(applicationException.getErrorCode())
                  .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
              assertThat(applicationException.getUserMessage())
                  .isEqualTo("Account type is required");
              assertThat(applicationException.getDetails()).containsEntry("type", "   ");
            });
  }

  private AccountController controller(
      AccountResolutionOwnerService accountResolutionOwnerService) {
    return new AccountController(
        accountResolutionOwnerService != null
            ? accountResolutionOwnerService
            : mock(AccountResolutionOwnerService.class));
  }
}
