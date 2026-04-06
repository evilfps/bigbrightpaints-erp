package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.service.CompanyScopedPurchasingLookupService;
import com.bigbrightpaints.erp.modules.sales.service.CompanyScopedSalesLookupService;

@ExtendWith(MockitoExtension.class)
class AccountResolutionServiceTest {

  @Mock private CompanyScopedSalesLookupService salesLookupService;
  @Mock private CompanyScopedPurchasingLookupService purchasingLookupService;
  @Mock private CompanyScopedAccountingLookupService accountingLookupService;
  @Mock private CompanyDefaultAccountsService companyDefaultAccountsService;
  @Mock private CompanyClock companyClock;

  @Test
  void resolveAutoSettlementCashAccountId_usesCompanyDefaultAccountsServiceConfiguration() {
    AccountResolutionService service =
        new AccountResolutionService(
            salesLookupService,
            purchasingLookupService,
            accountingLookupService,
            companyDefaultAccountsService,
            companyClock);
    Company company = new Company();
    company.setCode("COA");
    when(
            companyDefaultAccountsService.resolveAutoSettlementCashAccountId(
                company, null, "dealer auto-settlement"))
        .thenReturn(88L);

    Long resolved = service.resolveAutoSettlementCashAccountId(company, null, "dealer auto-settlement");

    assertThat(resolved).isEqualTo(88L);
    verify(companyDefaultAccountsService)
        .resolveAutoSettlementCashAccountId(company, null, "dealer auto-settlement");
  }
}
