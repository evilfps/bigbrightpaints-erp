package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingBucketDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingSummaryResponse;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DunningServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private DealerRepository dealerRepository;
    @Mock
    private StatementService statementService;
    @Mock
    private CompanyClock companyClock;
    @Mock
    private EmailService emailService;

    private DunningService dunningService;
    private Company company;
    private Dealer dealer;

    @BeforeEach
    void setUp() {
        dunningService = new DunningService(
                companyContextService,
                companyRepository,
                dealerRepository,
                statementService,
                companyClock,
                emailService
        );

        company = new Company();
        company.setCode("TEST");

        dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setCode("DLR-01");
        dealer.setName("Dealer One");
        dealer.setEmail("dealer@example.com");

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(dealerRepository.findByCompanyAndId(company, 1L)).thenReturn(Optional.of(dealer));
        when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 23));
    }

    @Test
    void evaluateDealerHold_setsOnHoldAndSendsReminderWhenOverdueThresholdExceeded() {
        when(statementService.dealerAging(eq(1L), eq(LocalDate.of(2026, 2, 23)), eq(null)))
                .thenReturn(new AgingSummaryResponse(
                        1L,
                        "Dealer One",
                        new BigDecimal("500"),
                        List.of(
                                new AgingBucketDto("0-30", 0, 30, BigDecimal.ZERO),
                                new AgingBucketDto("45+", 45, null, new BigDecimal("500"))
                        )
                ));

        boolean placed = dunningService.evaluateDealerHold(1L, 45, new BigDecimal("100"));

        assertThat(placed).isTrue();
        assertThat(dealer.getStatus()).isEqualTo("ON_HOLD");
        verify(dealerRepository).save(dealer);

        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendSimpleEmail(eq("dealer@example.com"), subjectCaptor.capture(), bodyCaptor.capture());
        assertThat(subjectCaptor.getValue()).contains("DLR-01");
        assertThat(bodyCaptor.getValue()).contains("500");
    }

    @Test
    void evaluateDealerHold_doesNotSendReminderWhenWithinThreshold() {
        when(statementService.dealerAging(eq(1L), eq(LocalDate.of(2026, 2, 23)), eq(null)))
                .thenReturn(new AgingSummaryResponse(
                        1L,
                        "Dealer One",
                        new BigDecimal("50"),
                        List.of(new AgingBucketDto("45+", 45, null, new BigDecimal("50"))
                        )
                ));

        boolean placed = dunningService.evaluateDealerHold(1L, 45, new BigDecimal("100"));

        assertThat(placed).isFalse();
        verify(dealerRepository, never()).save(any(Dealer.class));
        verify(emailService, never()).sendSimpleEmail(any(), any(), any());
    }
}
