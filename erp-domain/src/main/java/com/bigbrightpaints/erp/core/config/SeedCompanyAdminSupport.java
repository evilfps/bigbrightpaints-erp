package com.bigbrightpaints.erp.core.config;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;

final class SeedCompanyAdminSupport {

  private SeedCompanyAdminSupport() {}

  static void attachMainAdmin(
      CompanyRepository companyRepository, Company company, UserAccount adminUser) {
    if (company == null || adminUser == null) {
      return;
    }
    Company targetCompany =
        company.getId() == null
            ? company
            : companyRepository.findById(company.getId()).orElse(company);
    targetCompany.setOnboardingAdminEmail(adminUser.getEmail());
    if (adminUser.getId() != null) {
      targetCompany.setMainAdminUserId(adminUser.getId());
      targetCompany.setOnboardingAdminUserId(adminUser.getId());
    }
    companyRepository.save(targetCompany);
  }
}
