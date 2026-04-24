package com.bigbrightpaints.erp.modules.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

class DealerRepositorySearchFilteredTest extends AbstractIntegrationTest {

  private static final PageRequest SEARCH_PAGE = PageRequest.of(0, 10);

  @Autowired private CompanyRepository companyRepository;

  @Autowired private DealerRepository dealerRepository;

  @Test
  @Transactional
  void searchFiltered_matchesEmailAndNameVariantsWithinTenantBoundary() {
    Company company = persistCompany("MOCK");
    Dealer target =
        persistDealer(
            company,
            "SHAREDDEALERCONVERGE20260424T054831Z98EAF2",
            "Shared Dealer Converge 20260424T054831Z-98eaf2",
            "shared-dealer-master-36-20260424t054831z-98eaf2@example.com");
    persistDealer(company, "OTHER-DEALER", "Other Dealer", "other.dealer@example.com");

    Company rivalCompany = persistCompany("RIVAL");
    Dealer rival =
        persistDealer(
            rivalCompany,
            "RIVALSHAREDDEALER20260424T054831Z98EAF2",
            "Shared Dealer Converge 20260424T054831Z-98eaf2",
            "shared-dealer-master-36-20260424t054831z-98eaf2@example.com");

    assertSearchContainsOnlyTarget(
        company,
        "shared-dealer-master-36-20260424T054831Z-98eaf2@example.com",
        target.getId(),
        rival.getId());
    assertSearchContainsOnlyTarget(
        company,
        "Shared Dealer Converge 20260424T054831Z-98eaf2",
        target.getId(),
        rival.getId());
    assertSearchContainsOnlyTarget(
        company, "20260424T054831Z-98eaf2", target.getId(), rival.getId());
    assertSearchContainsOnlyTarget(
        company,
        "Converge 20260424T054831Z-98eaf2",
        target.getId(),
        rival.getId());
  }

  private void assertSearchContainsOnlyTarget(
      Company company, String term, Long expectedDealerId, Long excludedDealerId) {
    List<Long> matchedIds =
        dealerRepository.searchFiltered(company, term, null, null, SEARCH_PAGE).stream()
            .map(Dealer::getId)
            .toList();

    assertThat(matchedIds).contains(expectedDealerId).doesNotContain(excludedDealerId);
  }

  private Company persistCompany(String codePrefix) {
    Company company = new Company();
    company.setName(codePrefix + " Search Coverage");
    company.setCode(codePrefix + "-" + UUID.randomUUID().toString().substring(0, 8));
    company.setTimezone("UTC");
    return companyRepository.saveAndFlush(company);
  }

  private Dealer persistDealer(Company company, String code, String name, String email) {
    Dealer dealer = new Dealer();
    dealer.setCompany(company);
    dealer.setCode(code);
    dealer.setName(name);
    dealer.setCompanyName(name + " Pvt Ltd");
    dealer.setEmail(email);
    dealer.setStatus("ACTIVE");
    return dealerRepository.saveAndFlush(dealer);
  }
}
