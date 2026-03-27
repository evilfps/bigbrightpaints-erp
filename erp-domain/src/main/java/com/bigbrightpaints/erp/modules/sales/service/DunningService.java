package com.bigbrightpaints.erp.modules.sales.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingBucketDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingSummaryResponse;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

@Service
public class DunningService {

  private static final Logger log = LoggerFactory.getLogger(DunningService.class);

  private final CompanyContextService companyContextService;
  private final CompanyRepository companyRepository;
  private final DealerRepository dealerRepository;
  private final StatementService statementService;
  private final CompanyClock companyClock;
  private final EmailService emailService;

  public DunningService(
      CompanyContextService companyContextService,
      CompanyRepository companyRepository,
      DealerRepository dealerRepository,
      StatementService statementService,
      CompanyClock companyClock,
      EmailService emailService) {
    this.companyContextService = companyContextService;
    this.companyRepository = companyRepository;
    this.dealerRepository = dealerRepository;
    this.statementService = statementService;
    this.companyClock = companyClock;
    this.emailService = emailService;
  }

  public boolean evaluateDealerHold(
      Long dealerId, int overdueDaysThreshold, BigDecimal overdueAmountThreshold) {
    Company company = companyContextService.requireCurrentCompany();
    Dealer dealer =
        dealerRepository
            .findByCompanyAndId(company, dealerId)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Dealer not found"));
    AgingSummaryResponse aging =
        statementService.dealerAging(dealerId, companyClock.today(company), null);
    BigDecimal overdue =
        aging.buckets().stream()
            .filter(b -> b.fromDays() >= overdueDaysThreshold)
            .map(AgingBucketDto::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (overdue.compareTo(overdueAmountThreshold) > 0) {
      dealer.setStatus("ON_HOLD");
      dealerRepository.save(dealer);
      sendOverdueReminder(dealer, overdue, overdueDaysThreshold);
      log.info("Dealer {} set to ON_HOLD due to overdue {}", dealer.getCode(), overdue);
      return true;
    }
    return false;
  }

  /**
   * Simple daily automation: put dealers on hold if >45 days bucket has balance.
   */
  @Scheduled(cron = "0 15 3 * * *")
  public void dailyDunningSweep() {
    companyRepository
        .findAll()
        .forEach(
            company -> {
              try {
                CompanyContextHolder.setCompanyCode(company.getCode());
                LocalDate today = companyClock.today(company);
                List<Dealer> dealers = dealerRepository.findByCompanyOrderByNameAsc(company);
                for (Dealer dealer : dealers) {
                  try {
                    AgingSummaryResponse aging =
                        statementService.dealerAging(dealer.getId(), today, null);
                    BigDecimal overdue =
                        aging.buckets().stream()
                            .filter(b -> b.fromDays() >= 45)
                            .map(AgingBucketDto::amount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    if (overdue.compareTo(BigDecimal.ZERO) > 0) {
                      if (!"ON_HOLD".equalsIgnoreCase(dealer.getStatus())) {
                        dealer.setStatus("ON_HOLD");
                        dealerRepository.save(dealer);
                        log.info(
                            "Dealer {} placed ON_HOLD by dunning sweep; overdue {}",
                            dealer.getCode(),
                            overdue);
                      }
                      sendOverdueReminder(dealer, overdue, 45);
                    }
                  } catch (Exception e) {
                    log.warn("Failed dunning evaluation for dealer {}", dealer.getCode(), e);
                  }
                }
              } catch (Exception e) {
                log.warn("Failed dunning sweep for company {}", company.getCode(), e);
              } finally {
                CompanyContextHolder.clear();
              }
            });
  }

  private void sendOverdueReminder(
      Dealer dealer, BigDecimal overdueAmount, int overdueDaysThreshold) {
    if (dealer == null || !org.springframework.util.StringUtils.hasText(dealer.getEmail())) {
      return;
    }
    String subject = "Overdue account reminder - " + dealer.getCode();
    String body =
        "Dear "
            + dealer.getName()
            + ",\n\n"
            + "Our records show overdue invoices above the threshold of "
            + overdueDaysThreshold
            + " days.\n"
            + "Total overdue amount: "
            + overdueAmount
            + "\n\n"
            + "Please arrange payment at the earliest or contact the accounts team.\n\n"
            + "Regards,\nBigBright ERP";
    try {
      emailService.sendSimpleEmail(dealer.getEmail(), subject, body);
    } catch (RuntimeException ex) {
      log.warn("Failed to send dunning reminder email for dealer {}", dealer.getCode(), ex);
    }
  }
}
