package com.bigbrightpaints.erp.modules.accounting.service;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;

@Component
public class AuditDigestScheduler {

  private static final Logger log = LoggerFactory.getLogger(AuditDigestScheduler.class);

  private final AccountingService accountingService;
  private final CompanyRepository companyRepository;
  private final CompanyClock companyClock;

  public AuditDigestScheduler(
      AccountingService accountingService,
      CompanyRepository companyRepository,
      CompanyClock companyClock) {
    this.accountingService = accountingService;
    this.companyRepository = companyRepository;
    this.companyClock = companyClock;
  }

  /**
   * Emit previous-day audit digest for each company at 02:30 server time.
   * Lightweight: logs lines; consumers can ship logs to SIEM.
   */
  @Scheduled(cron = "0 30 2 * * *")
  public void publishDailyDigest() {
    companyRepository
        .findAll()
        .forEach(
            company -> {
              try {
                CompanyContextHolder.setCompanyCode(company.getCode());
                LocalDate yesterday = companyClock.today(company).minusDays(1);
                var digest = accountingService.auditDigest(yesterday, yesterday);
                if (digest.entries().isEmpty()) {
                  return;
                }
                digest
                    .entries()
                    .forEach(
                        line ->
                            log.info(
                                "[AUDIT-DIGEST] company={} period={} {}",
                                company.getCode(),
                                digest.periodLabel(),
                                line));
              } catch (Exception ex) {
                log.warn("Failed to emit audit digest for company {}", company.getCode(), ex);
              } finally {
                CompanyContextHolder.clear();
              }
            });
  }
}
