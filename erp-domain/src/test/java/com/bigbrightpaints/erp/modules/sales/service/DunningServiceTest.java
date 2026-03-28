package com.bigbrightpaints.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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

@ExtendWith(MockitoExtension.class)
class DunningServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private CompanyRepository companyRepository;
  @Mock private DealerRepository dealerRepository;
  @Mock private StatementService statementService;
  @Mock private CompanyClock companyClock;
  @Mock private EmailService emailService;

  private DunningService dunningService;
  private Company company;
  private Dealer dealer;

  @BeforeEach
  void setUp() {
    dunningService =
        new DunningService(
            companyContextService,
            companyRepository,
            dealerRepository,
            statementService,
            companyClock,
            emailService);

    company = new Company();
    company.setCode("TEST");

    dealer = new Dealer();
    dealer.setCompany(company);
    dealer.setCode("DLR-01");
    dealer.setName("Dealer One");
    dealer.setEmail("dealer@example.com");
  }

  @Test
  void evaluateDealerHold_setsOnHoldAndSendsReminderWhenOverdueThresholdExceeded() {
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(dealerRepository.findByCompanyAndId(company, 1L)).thenReturn(Optional.of(dealer));
    when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 23));
    when(statementService.dealerAging(eq(1L), eq(LocalDate.of(2026, 2, 23)), eq(null)))
        .thenReturn(
            new AgingSummaryResponse(
                1L,
                "Dealer One",
                new BigDecimal("500"),
                List.of(
                    new AgingBucketDto("0-30", 0, 30, BigDecimal.ZERO),
                    new AgingBucketDto("45+", 45, null, new BigDecimal("500")))));

    boolean placed = dunningService.evaluateDealerHold(1L, 45, new BigDecimal("100"));

    assertThat(placed).isTrue();
    assertThat(dealer.getStatus()).isEqualTo("ON_HOLD");
    verify(dealerRepository).save(dealer);

    ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
    verify(emailService)
        .sendSimpleEmail(eq("dealer@example.com"), subjectCaptor.capture(), bodyCaptor.capture());
    assertThat(subjectCaptor.getValue()).contains("DLR-01");
    assertThat(bodyCaptor.getValue()).contains("500");
  }

  @Test
  void evaluateDealerHold_doesNotSendReminderWhenWithinThreshold() {
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(dealerRepository.findByCompanyAndId(company, 1L)).thenReturn(Optional.of(dealer));
    when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 23));
    when(statementService.dealerAging(eq(1L), eq(LocalDate.of(2026, 2, 23)), eq(null)))
        .thenReturn(
            new AgingSummaryResponse(
                1L,
                "Dealer One",
                new BigDecimal("50"),
                List.of(new AgingBucketDto("45+", 45, null, new BigDecimal("50")))));

    boolean placed = dunningService.evaluateDealerHold(1L, 45, new BigDecimal("100"));

    assertThat(placed).isFalse();
    verify(dealerRepository, never()).save(any(Dealer.class));
    verify(emailService, never()).sendSimpleEmail(any(), any(), any());
  }

  @Test
  void dailyDunningSweep_setsCompanyCodeContextBeforeDealerAging() {
    ReflectionTestUtils.setField(dealer, "id", 1L);
    dealer.setStatus("ACTIVE");
    when(companyRepository.findAll()).thenReturn(List.of(company));
    when(dealerRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of(dealer));
    when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 23));
    when(statementService.dealerAging(eq(1L), eq(LocalDate.of(2026, 2, 23)), eq(null)))
        .thenAnswer(
            invocation -> {
              assertThat(CompanyContextHolder.getCompanyCode()).isEqualTo("TEST");
              return new AgingSummaryResponse(
                  1L,
                  "Dealer One",
                  BigDecimal.ZERO,
                  List.of(new AgingBucketDto("45+", 45, null, BigDecimal.ZERO)));
            });

    dunningService.dailyDunningSweep();

    assertThat(CompanyContextHolder.getCompanyCode()).isNull();
  }
}
