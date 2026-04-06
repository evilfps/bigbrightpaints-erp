package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

  private Company company(Long id, String code) {
    Company company = new Company();
    company.setCode(code);
    company.setName(code + " Company");
    ReflectionTestUtils.setField(company, "id", id);
    return company;
  }

  private Account account(Long id, Company company, String code, AccountType type) {
    Account account = new Account();
    ReflectionTestUtils.setField(account, "id", id);
    account.setCompany(company);
    account.setCode(code);
    account.setName(code + " Name");
    account.setType(type);
    return account;
  }
}
