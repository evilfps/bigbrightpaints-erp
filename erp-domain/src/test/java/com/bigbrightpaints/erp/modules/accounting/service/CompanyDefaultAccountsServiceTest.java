package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

class CompanyDefaultAccountsServiceTest {

  private CompanyContextService companyContextService;
  private CompanyScopedAccountingLookupService accountingLookupService;
  private CompanyRepository companyRepository;
  private CompanyDefaultAccountsService service;
  private Company company;

  @BeforeEach
  void setUp() {
    companyContextService = Mockito.mock(CompanyContextService.class);
    accountingLookupService = Mockito.mock(CompanyScopedAccountingLookupService.class);
    companyRepository = Mockito.mock(CompanyRepository.class);
    service =
        new CompanyDefaultAccountsService(
            companyContextService, accountingLookupService, companyRepository);
    company = new Company();
    company.setCode("BBP");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
  }

  @Test
  void requireDefaults_missingInventory_throws() {
    company.setDefaultCogsAccountId(2L);
    company.setDefaultRevenueAccountId(3L);
    company.setDefaultDiscountAccountId(5L);
    company.setDefaultTaxAccountId(4L);
    assertThatThrownBy(() -> service.requireDefaults())
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("fgValuationAccountId")
        .hasMessageContaining("BBP");
  }

  @Test
  void requireDefaults_missingCogs_throws() {
    company.setDefaultInventoryAccountId(1L);
    company.setDefaultRevenueAccountId(3L);
    company.setDefaultDiscountAccountId(5L);
    company.setDefaultTaxAccountId(4L);
    assertThatThrownBy(() -> service.requireDefaults())
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("fgCogsAccountId")
        .hasMessageContaining("BBP");
  }

  @Test
  void requireDefaults_missingRevenue_throws() {
    company.setDefaultInventoryAccountId(1L);
    company.setDefaultCogsAccountId(2L);
    company.setDefaultDiscountAccountId(5L);
    company.setDefaultTaxAccountId(4L);
    assertThatThrownBy(() -> service.requireDefaults())
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("fgRevenueAccountId")
        .hasMessageContaining("BBP");
  }

  @Test
  void requireDefaults_missingDiscount_throws() {
    company.setDefaultInventoryAccountId(1L);
    company.setDefaultCogsAccountId(2L);
    company.setDefaultRevenueAccountId(3L);
    company.setDefaultTaxAccountId(4L);
    assertThatThrownBy(() -> service.requireDefaults())
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("fgDiscountAccountId")
        .hasMessageContaining("BBP");
  }

  @Test
  void requireDefaults_missingTax_throws() {
    company.setDefaultInventoryAccountId(1L);
    company.setDefaultCogsAccountId(2L);
    company.setDefaultRevenueAccountId(3L);
    company.setDefaultDiscountAccountId(5L);
    assertThatThrownBy(() -> service.requireDefaults())
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("fgTaxAccountId")
        .hasMessageContaining("BBP");
  }

  @Test
  void requireDefaults_allSet_returnsDefaults() {
    company.setDefaultInventoryAccountId(1L);
    company.setDefaultCogsAccountId(2L);
    company.setDefaultRevenueAccountId(3L);
    company.setDefaultDiscountAccountId(5L);
    company.setDefaultTaxAccountId(4L);
    CompanyDefaultAccountsService.DefaultAccounts defaults = service.requireDefaults();
    assertThat(defaults.inventoryAccountId()).isEqualTo(1L);
    assertThat(defaults.cogsAccountId()).isEqualTo(2L);
    assertThat(defaults.revenueAccountId()).isEqualTo(3L);
    assertThat(defaults.discountAccountId()).isEqualTo(5L);
    assertThat(defaults.fgDiscountAccountId()).isEqualTo(5L);
    assertThat(defaults.taxAccountId()).isEqualTo(4L);
  }

  @Test
  void getDefaults_returnsSnapshot() {
    company.setDefaultInventoryAccountId(10L);
    company.setDefaultCogsAccountId(20L);
    company.setDefaultRevenueAccountId(30L);
    company.setDefaultDiscountAccountId(40L);
    company.setDefaultTaxAccountId(50L);
    CompanyDefaultAccountsService.DefaultAccounts defaults = service.getDefaults();
    assertThat(defaults.inventoryAccountId()).isEqualTo(10L);
    assertThat(defaults.cogsAccountId()).isEqualTo(20L);
    assertThat(defaults.revenueAccountId()).isEqualTo(30L);
    assertThat(defaults.discountAccountId()).isEqualTo(40L);
    assertThat(defaults.fgDiscountAccountId()).isEqualTo(40L);
    assertThat(defaults.taxAccountId()).isEqualTo(50L);
  }

  @Test
  void updateDefaults_setsInventoryAccount() {
    Account inventory = account(11L, AccountType.ASSET, "INV");
    when(accountingLookupService.requireAccount(company, 11L)).thenReturn(inventory);
    CompanyDefaultAccountsService.DefaultAccounts defaults =
        service.updateDefaults(11L, null, null, null, null, null);
    assertThat(defaults.inventoryAccountId()).isEqualTo(11L);
    verify(companyRepository).save(company);
  }

  @Test
  void updateDefaults_rejectsInventoryWrongType() {
    Account inventory = account(12L, AccountType.REVENUE, "REV");
    when(accountingLookupService.requireAccount(company, 12L)).thenReturn(inventory);
    assertThatThrownBy(() -> service.updateDefaults(12L, null, null, null, null, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("inventory");
  }

  @Test
  void updateDefaults_setsCogsAccount() {
    Account cogs = account(21L, AccountType.COGS, "COGS");
    when(accountingLookupService.requireAccount(company, 21L)).thenReturn(cogs);
    CompanyDefaultAccountsService.DefaultAccounts defaults =
        service.updateDefaults(null, 21L, null, null, null, null);
    assertThat(defaults.cogsAccountId()).isEqualTo(21L);
    verify(companyRepository).save(company);
  }

  @Test
  void updateDefaults_rejectsCogsWrongType() {
    Account cogs = account(22L, AccountType.ASSET, "INV");
    when(accountingLookupService.requireAccount(company, 22L)).thenReturn(cogs);
    assertThatThrownBy(() -> service.updateDefaults(null, 22L, null, null, null, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("COGS");
  }

  @Test
  void updateDefaults_setsRevenueAccount() {
    Account revenue = account(31L, AccountType.REVENUE, "REV");
    when(accountingLookupService.requireAccount(company, 31L)).thenReturn(revenue);
    CompanyDefaultAccountsService.DefaultAccounts defaults =
        service.updateDefaults(null, null, 31L, null, null, null);
    assertThat(defaults.revenueAccountId()).isEqualTo(31L);
    verify(companyRepository).save(company);
  }

  @Test
  void updateDefaults_rejectsRevenueWrongType() {
    Account revenue = account(32L, AccountType.COGS, "COGS");
    when(accountingLookupService.requireAccount(company, 32L)).thenReturn(revenue);
    assertThatThrownBy(() -> service.updateDefaults(null, null, 32L, null, null, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("revenue");
  }

  @Test
  void updateDefaults_allowsDiscountRevenue() {
    Account discount = account(41L, AccountType.REVENUE, "DISC");
    when(accountingLookupService.requireAccount(company, 41L)).thenReturn(discount);
    CompanyDefaultAccountsService.DefaultAccounts defaults =
        service.updateDefaults(null, null, null, 41L, null, null);
    assertThat(defaults.discountAccountId()).isEqualTo(41L);
    verify(companyRepository).save(company);
  }

  @Test
  void updateDefaults_allowsDiscountExpense() {
    Account discount = account(42L, AccountType.EXPENSE, "DISC");
    when(accountingLookupService.requireAccount(company, 42L)).thenReturn(discount);
    CompanyDefaultAccountsService.DefaultAccounts defaults =
        service.updateDefaults(null, null, null, 42L, null, null);
    assertThat(defaults.discountAccountId()).isEqualTo(42L);
    verify(companyRepository).save(company);
  }

  @Test
  void updateDefaults_rejectsDiscountWrongType() {
    Account discount = account(43L, AccountType.ASSET, "INV");
    when(accountingLookupService.requireAccount(company, 43L)).thenReturn(discount);
    assertThatThrownBy(() -> service.updateDefaults(null, null, null, 43L, null, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Discount");
  }

  @Test
  void updateDefaults_allowsFgDiscountAlias() {
    Account discount = account(44L, AccountType.EXPENSE, "DISC");
    when(accountingLookupService.requireAccount(company, 44L)).thenReturn(discount);
    CompanyDefaultAccountsService.DefaultAccounts defaults =
        service.updateDefaults(null, null, null, null, 44L, null);
    assertThat(defaults.discountAccountId()).isEqualTo(44L);
    assertThat(defaults.fgDiscountAccountId()).isEqualTo(44L);
    verify(companyRepository).save(company);
  }

  @Test
  void updateDefaults_rejectsDiscountAndFgDiscountMismatch() {
    assertThatThrownBy(() -> service.updateDefaults(null, null, null, 41L, 42L, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("must match");
  }

  @Test
  void updateDefaults_setsTaxAccount() {
    company.setDefaultGstRate(new java.math.BigDecimal("18.00"));
    Account tax = account(51L, AccountType.LIABILITY, "GST-OUT");
    when(accountingLookupService.requireAccount(company, 51L)).thenReturn(tax);
    CompanyDefaultAccountsService.DefaultAccounts defaults =
        service.updateDefaults(null, null, null, null, null, 51L);
    assertThat(defaults.taxAccountId()).isEqualTo(51L);
    assertThat(company.getGstOutputTaxAccountId()).isEqualTo(51L);
    assertThat(company.getGstPayableAccountId()).isEqualTo(51L);
    verify(companyRepository).save(company);
  }

  @Test
  void updateDefaults_setsGstOutputAndPayableWhenGstRateIsUnset() {
    company.setDefaultGstRate(null);
    company.setGstInputTaxAccountId(88L);
    Account tax = account(54L, AccountType.LIABILITY, "GST-OUT");
    when(accountingLookupService.requireAccount(company, 54L)).thenReturn(tax);

    CompanyDefaultAccountsService.DefaultAccounts defaults =
        service.updateDefaults(null, null, null, null, null, 54L);

    assertThat(defaults.taxAccountId()).isEqualTo(54L);
    assertThat(company.getGstInputTaxAccountId()).isEqualTo(88L);
    assertThat(company.getGstOutputTaxAccountId()).isEqualTo(54L);
    assertThat(company.getGstPayableAccountId()).isEqualTo(54L);
    verify(companyRepository).save(company);
  }

  @Test
  void updateDefaults_clearsGstAccountsForNonGstCompany() {
    company.setDefaultGstRate(java.math.BigDecimal.ZERO);
    company.setGstInputTaxAccountId(98L);
    company.setGstOutputTaxAccountId(99L);
    company.setGstPayableAccountId(100L);
    Account tax = account(52L, AccountType.LIABILITY, "TAX-PAYABLE");
    when(accountingLookupService.requireAccount(company, 52L)).thenReturn(tax);

    CompanyDefaultAccountsService.DefaultAccounts defaults =
        service.updateDefaults(null, null, null, null, null, 52L);

    assertThat(defaults.taxAccountId()).isEqualTo(52L);
    assertThat(company.getGstInputTaxAccountId()).isNull();
    assertThat(company.getGstOutputTaxAccountId()).isNull();
    assertThat(company.getGstPayableAccountId()).isNull();
    verify(companyRepository).save(company);
  }

  @Test
  void updateDefaults_rejectsTaxWrongType() {
    Account tax = account(53L, AccountType.ASSET, "INV");
    when(accountingLookupService.requireAccount(company, 53L)).thenReturn(tax);
    assertThatThrownBy(() -> service.updateDefaults(null, null, null, null, null, 53L))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("tax");
  }

  @Test
  void resolveAutoSettlementCashAccountId_requiresExplicitCashAccountId() {
    company.setPayrollCashAccount(account(88L, AccountType.ASSET, "PAYROLL-CASH"));

    assertThatThrownBy(
            () ->
                service.resolveAutoSettlementCashAccountId(company, null, "dealer auto-settlement"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("cashAccountId is required for dealer auto-settlement");
  }

  @Test
  void resolveAutoSettlementCashAccountId_validatesExplicitCashAccount() {
    Account cash = account(91L, AccountType.ASSET, "BANK-CASH");
    when(accountingLookupService.requireAccount(company, 91L)).thenReturn(cash);

    Long resolved =
        service.resolveAutoSettlementCashAccountId(company, 91L, "supplier auto-settlement");

    assertThat(resolved).isEqualTo(91L);
    verify(accountingLookupService).requireAccount(company, 91L);
  }

  private Account account(Long id, AccountType type, String code) {
    Account account = new Account();
    account.setType(type);
    account.setCode(code);
    setId(account, id);
    return account;
  }

  private void setId(Account account, Long id) {
    try {
      Field field = Account.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(account, id);
    } catch (NoSuchFieldException | IllegalAccessException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
