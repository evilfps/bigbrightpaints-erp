package com.bigbrightpaints.erp.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.bigbrightpaints.erp.core.service.CriticalFixtureService;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;

@Configuration
@Profile({"test", "mock", "dev"})
public class CriticalFixtureInitializer {

  private static final Logger log = LoggerFactory.getLogger(CriticalFixtureInitializer.class);

  @Bean
  CommandLineRunner seedCriticalFixtures(
      CompanyRepository companyRepository, CriticalFixtureService criticalFixtureService) {
    return args -> {
      var companies = companyRepository.findAll();
      if (companies.isEmpty()) {
        log.info("Skipping critical fixture seeding: no companies present");
        return;
      }
      for (var company : companies) {
        try {
          criticalFixtureService.seedCompanyFixtures(company);
        } catch (Exception ex) {
          throw new IllegalStateException(
              "Failed to seed critical fixtures for company " + company.getCode(), ex);
        }
      }
    };
  }
}
