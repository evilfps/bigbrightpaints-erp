package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyScopedAccountingLookupService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

@Tag("concurrency")
@Tag("reconciliation")
class TS_RuntimeCompanyDefaultAccountsExecutableCoverageTest {

  @Test
  void update_defaults_accepts_valid_types_and_exposes_get_and_require_defaults() {
    Company company = company(100L, "DEF");
    company.setDefaultGstRate(new java.math.BigDecimal("18.00"));
    CompanyContextService companyContextService = mock(CompanyContextService.class);
    CompanyScopedAccountingLookupService accountingLookupService =
        mock(CompanyScopedAccountingLookupService.class);
    CompanyRepository companyRepository = mock(CompanyRepository.class);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(companyRepository.save(any(Company.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    when(accountingLookupService.requireAccount(company, 1L))
        .thenReturn(account(1L, company, "INV", AccountType.ASSET));
    when(accountingLookupService.requireAccount(company, 2L))
        .thenReturn(account(2L, company, "COGS", AccountType.COGS));
    when(accountingLookupService.requireAccount(company, 3L))
        .thenReturn(account(3L, company, "REV", AccountType.REVENUE));
    when(accountingLookupService.requireAccount(company, 4L))
        .thenReturn(account(4L, company, "DISC", AccountType.EXPENSE));
    when(accountingLookupService.requireAccount(company, 5L))
        .thenReturn(account(5L, company, "GST", AccountType.LIABILITY));
    when(accountingLookupService.requireAccount(company, 6L))
        .thenReturn(account(6L, company, "DISC-REV", AccountType.REVENUE));

    CompanyDefaultAccountsService service =
        new CompanyDefaultAccountsService(
            companyContextService, accountingLookupService, companyRepository);

    CompanyDefaultAccountsService.DefaultAccounts updated =
        service.updateDefaults(1L, 2L, 3L, 4L, null, 5L);
    assertThat(updated.inventoryAccountId()).isEqualTo(1L);
    assertThat(updated.cogsAccountId()).isEqualTo(2L);
    assertThat(updated.revenueAccountId()).isEqualTo(3L);
    assertThat(updated.discountAccountId()).isEqualTo(4L);
    assertThat(updated.fgDiscountAccountId()).isEqualTo(4L);
    assertThat(updated.taxAccountId()).isEqualTo(5L);
    assertThat(company.getGstOutputTaxAccountId()).isEqualTo(5L);

    CompanyDefaultAccountsService.DefaultAccounts current = service.getDefaults();
    assertThat(current.inventoryAccountId()).isEqualTo(1L);

    CompanyDefaultAccountsService.DefaultAccounts required = service.requireDefaults();
    assertThat(required.taxAccountId()).isEqualTo(5L);

    CompanyDefaultAccountsService.DefaultAccounts revenueDiscount =
        service.updateDefaults(null, null, null, 6L, null, null);
    assertThat(revenueDiscount.discountAccountId()).isEqualTo(6L);
    assertThat(revenueDiscount.fgDiscountAccountId()).isEqualTo(6L);
  }

  @Test
  void update_defaults_rejects_invalid_types_for_each_account_purpose() {
    Company company = company(200L, "DEF2");
    CompanyContextService companyContextService = mock(CompanyContextService.class);
    CompanyScopedAccountingLookupService accountingLookupService =
        mock(CompanyScopedAccountingLookupService.class);
    CompanyRepository companyRepository = mock(CompanyRepository.class);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(companyRepository.save(any(Company.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    when(accountingLookupService.requireAccount(company, 10L))
        .thenReturn(account(10L, company, "BAD-INV", AccountType.LIABILITY));
    when(accountingLookupService.requireAccount(company, 11L))
        .thenReturn(account(11L, company, "BAD-COGS", AccountType.ASSET));
    when(accountingLookupService.requireAccount(company, 12L))
        .thenReturn(account(12L, company, "BAD-REV", AccountType.ASSET));
    when(accountingLookupService.requireAccount(company, 13L))
        .thenReturn(account(13L, company, "BAD-DISC", AccountType.LIABILITY));
    when(accountingLookupService.requireAccount(company, 14L))
        .thenReturn(account(14L, company, "BAD-TAX", AccountType.ASSET));

    CompanyDefaultAccountsService service =
        new CompanyDefaultAccountsService(
            companyContextService, accountingLookupService, companyRepository);

    assertThatThrownBy(() -> service.updateDefaults(10L, null, null, null, null, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("valid inventory account");

    assertThatThrownBy(() -> service.updateDefaults(null, 11L, null, null, null, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("valid COGS account");

    assertThatThrownBy(() -> service.updateDefaults(null, null, 12L, null, null, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("valid revenue account");

    assertThatThrownBy(() -> service.updateDefaults(null, null, null, 13L, null, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Discount account must be revenue or expense");

    assertThatThrownBy(() -> service.updateDefaults(null, null, null, null, null, 14L))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("expected type LIABILITY");
  }

  @Test
  void require_defaults_fails_when_required_accounts_are_missing() {
    Company company = company(300L, "DEF3");
    company.setDefaultInventoryAccountId(1L);
    company.setDefaultCogsAccountId(2L);
    company.setDefaultRevenueAccountId(null);
    company.setDefaultTaxAccountId(5L);

    CompanyContextService companyContextService = mock(CompanyContextService.class);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);

    CompanyDefaultAccountsService service =
        new CompanyDefaultAccountsService(
            companyContextService,
            mock(CompanyScopedAccountingLookupService.class),
            mock(CompanyRepository.class));

    assertThatThrownBy(service::requireDefaults)
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("fgRevenueAccountId")
        .hasMessageContaining("DEF3");

    Company inventoryMissing = company(301L, "DEF4");
    inventoryMissing.setDefaultInventoryAccountId(null);
    inventoryMissing.setDefaultCogsAccountId(2L);
    inventoryMissing.setDefaultRevenueAccountId(3L);
    inventoryMissing.setDefaultTaxAccountId(4L);
    when(companyContextService.requireCurrentCompany()).thenReturn(inventoryMissing);
    assertThatThrownBy(service::requireDefaults)
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("fgValuationAccountId")
        .hasMessageContaining("DEF4");
  }

  @Test
  void update_defaults_covers_non_gst_tax_reset_discount_alias_and_discount_mismatch() {
    Company company = company(400L, "DEF5");
    company.setDefaultInventoryAccountId(10L);
    company.setDefaultCogsAccountId(20L);
    company.setDefaultRevenueAccountId(30L);
    company.setDefaultDiscountAccountId(40L);
    company.setDefaultTaxAccountId(50L);
    company.setDefaultGstRate(java.math.BigDecimal.ZERO);
    company.setGstInputTaxAccountId(81L);
    company.setGstOutputTaxAccountId(82L);
    company.setGstPayableAccountId(83L);

    CompanyContextService companyContextService = mock(CompanyContextService.class);
    CompanyScopedAccountingLookupService accountingLookupService =
        mock(CompanyScopedAccountingLookupService.class);
    CompanyRepository companyRepository = mock(CompanyRepository.class);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(companyRepository.save(any(Company.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(accountingLookupService.requireAccount(company, 41L))
        .thenReturn(account(41L, company, "DISC", AccountType.EXPENSE));
    when(accountingLookupService.requireAccount(company, 51L))
        .thenReturn(account(51L, company, "GST-PAYABLE", AccountType.LIABILITY));

    CompanyDefaultAccountsService service =
        new CompanyDefaultAccountsService(
            companyContextService, accountingLookupService, companyRepository);

    CompanyDefaultAccountsService.DefaultAccounts unchanged =
        service.updateDefaults(null, null, null, null, null, null);
    assertThat(unchanged.inventoryAccountId()).isEqualTo(10L);
    assertThat(unchanged.taxAccountId()).isEqualTo(50L);

    CompanyDefaultAccountsService.DefaultAccounts aliased =
        service.updateDefaults(null, null, null, null, 41L, 51L);
    assertThat(aliased.discountAccountId()).isEqualTo(41L);
    assertThat(aliased.fgDiscountAccountId()).isEqualTo(41L);
    assertThat(company.getGstInputTaxAccountId()).isNull();
    assertThat(company.getGstOutputTaxAccountId()).isNull();
    assertThat(company.getGstPayableAccountId()).isNull();

    assertThatThrownBy(() -> service.updateDefaults(null, null, null, 41L, 42L, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("must match");
  }

  @Test
  void resolve_auto_settlement_cash_account_covers_required_active_asset_and_control_guardrails() {
    Company company = company(500L, "DEF6");
    CompanyScopedAccountingLookupService accountingLookupService =
        mock(CompanyScopedAccountingLookupService.class);
    CompanyDefaultAccountsService service =
        new CompanyDefaultAccountsService(
            mock(CompanyContextService.class),
            accountingLookupService,
            mock(CompanyRepository.class));

    assertThatThrownBy(() -> service.resolveAutoSettlementCashAccountId(null, 99L, "dealer"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Company context is required");

    assertThatThrownBy(() -> service.resolveAutoSettlementCashAccountId(company, null, "dealer"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("cashAccountId is required for dealer");

    when(accountingLookupService.requireAccount(company, 91L))
        .thenReturn(account(null, company, "BANK", AccountType.ASSET, "Bank", true));
    assertThatThrownBy(() -> service.resolveAutoSettlementCashAccountId(company, 91L, "dealer"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("cashAccountId is required for dealer");

    when(accountingLookupService.requireAccount(company, 92L))
        .thenReturn(account(92L, company, "BANK", AccountType.ASSET, "Bank", false));
    assertThatThrownBy(() -> service.resolveAutoSettlementCashAccountId(company, 92L, "dealer"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("must be active");

    when(accountingLookupService.requireAccount(company, 93L))
        .thenReturn(account(93L, company, "AP", AccountType.LIABILITY, "Accounts Payable", true));
    assertThatThrownBy(() -> service.resolveAutoSettlementCashAccountId(company, 93L, "dealer"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("must be an ASSET account");

    when(accountingLookupService.requireAccount(company, 94L))
        .thenReturn(
            account(94L, company, "AR-CTRL", AccountType.ASSET, "Accounts Receivable Main", true));
    assertThatThrownBy(() -> service.resolveAutoSettlementCashAccountId(company, 94L, "dealer"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("cannot be AR/AP control account");

    when(accountingLookupService.requireAccount(company, 95L))
        .thenReturn(account(95L, company, "BANK-CASH", null, "Primary Bank", true));
    assertThat(service.resolveAutoSettlementCashAccountId(company, 95L, "dealer")).isEqualTo(95L);
  }

  @Test
  void helper_predicates_cover_receivable_payable_and_token_shape_branches() {
    CompanyDefaultAccountsService service =
        new CompanyDefaultAccountsService(
            mock(CompanyContextService.class),
            mock(CompanyScopedAccountingLookupService.class),
            mock(CompanyRepository.class));

    assertThat(invokeTokenMatch(service, "AR", "AR")).isTrue();
    assertThat(invokeTokenMatch(service, "AR-CTRL", "AR")).isTrue();
    assertThat(invokeTokenMatch(service, "CTRL-AR", "AR")).isTrue();
    assertThat(invokeTokenMatch(service, "CTRL-AR-MAIN", "AR")).isTrue();
    assertThat(invokeTokenMatch(service, null, "AR")).isFalse();
    assertThat(invokeTokenMatch(service, "   ", "AR")).isFalse();
    assertThat(invokeTokenMatch(service, "BANK", "AR")).isFalse();

    assertThat(
            invokeAccountPredicate(
                service,
                "isReceivableAccount",
                account(
                    96L, company(501L, "DEF7"), "AR-CTRL", AccountType.ASSET, "Receivable", true)))
        .isTrue();
    assertThat(
            invokeAccountPredicate(
                service,
                "isReceivableAccount",
                account(
                    97L,
                    company(502L, "DEF8"),
                    "BANK",
                    AccountType.ASSET,
                    "Accounts Receivable Main",
                    true)))
        .isTrue();
    assertThat(
            invokeAccountPredicate(
                service,
                "isReceivableAccount",
                account(
                    98L,
                    company(503L, "DEF9"),
                    "AR-CTRL",
                    AccountType.LIABILITY,
                    "Accounts Receivable Main",
                    true)))
        .isFalse();
    assertThat(invokeAccountPredicate(service, "isReceivableAccount", null)).isFalse();

    assertThat(
            invokeAccountPredicate(
                service,
                "isPayableAccount",
                account(
                    99L,
                    company(504L, "DEF10"),
                    "AP-CTRL",
                    AccountType.LIABILITY,
                    "Trade Payables",
                    true)))
        .isTrue();
    assertThat(
            invokeAccountPredicate(
                service,
                "isPayableAccount",
                account(
                    100L,
                    company(505L, "DEF11"),
                    "GST",
                    AccountType.LIABILITY,
                    "Accounts Payable Main",
                    true)))
        .isTrue();
    assertThat(
            invokeAccountPredicate(
                service,
                "isPayableAccount",
                account(
                    101L,
                    company(506L, "DEF12"),
                    "AP-CTRL",
                    AccountType.ASSET,
                    "Accounts Payable Main",
                    true)))
        .isFalse();
    assertThat(invokeAccountPredicate(service, "isPayableAccount", null)).isFalse();
  }

  private Company company(Long id, String code) {
    Company company = new Company();
    company.setCode(code);
    company.setName(code + " Company");
    ReflectionTestUtils.setField(company, "id", id);
    return company;
  }

  private Account account(Long id, Company company, String code, AccountType type) {
    return account(id, company, code, type, code + " Name", true);
  }

  private Account account(
      Long id, Company company, String code, AccountType type, String name, boolean active) {
    Account account = new Account();
    ReflectionTestUtils.setField(account, "id", id);
    account.setCompany(company);
    account.setCode(code);
    account.setName(name);
    account.setType(type);
    account.setActive(active);
    return account;
  }

  private boolean invokeTokenMatch(
      CompanyDefaultAccountsService service, String value, String token) {
    try {
      Method method =
          CompanyDefaultAccountsService.class.getDeclaredMethod(
              "isTokenMatch", String.class, String.class);
      method.setAccessible(true);
      return (boolean) method.invoke(service, value, token);
    } catch (InvocationTargetException ex) {
      throw propagateInvocationTarget("isTokenMatch", ex);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException("Failed to invoke isTokenMatch reflectively", ex);
    }
  }

  private boolean invokeAccountPredicate(
      CompanyDefaultAccountsService service, String methodName, Account account) {
    try {
      Method method =
          CompanyDefaultAccountsService.class.getDeclaredMethod(methodName, Account.class);
      method.setAccessible(true);
      return (boolean) method.invoke(service, account);
    } catch (InvocationTargetException ex) {
      throw propagateInvocationTarget(methodName, ex);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException("Failed to invoke " + methodName + " reflectively", ex);
    }
  }

  private RuntimeException propagateInvocationTarget(
      String methodName, InvocationTargetException ex) {
    Throwable cause = ex.getCause();
    if (cause instanceof RuntimeException runtimeException) {
      return runtimeException;
    }
    if (cause instanceof Error error) {
      throw error;
    }
    return new IllegalStateException(
        "Invocation of " + methodName + " failed", cause != null ? cause : ex);
  }
}
