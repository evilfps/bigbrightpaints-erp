package com.bigbrightpaints.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingBucketDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingSummaryResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.OverdueInvoiceDto;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoicePdfService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class DealerPortalServiceTest {

  @Mock private DealerRepository dealerRepository;
  @Mock private CompanyContextService companyContextService;
  @Mock private DealerLedgerService dealerLedgerService;
  @Mock private InvoiceRepository invoiceRepository;
  @Mock private InvoicePdfService invoicePdfService;
  @Mock private DealerService dealerService;
  @Mock private SalesOrderRepository salesOrderRepository;
  @Mock private CompanyClock companyClock;
  @Mock private StatementService statementService;

  private DealerPortalService dealerPortalService;
  private Company company;

  @BeforeEach
  void setUp() {
    dealerPortalService =
        new DealerPortalService(
            dealerRepository,
            companyContextService,
            dealerLedgerService,
            invoiceRepository,
            invoicePdfService,
            dealerService,
            salesOrderRepository,
            companyClock,
            statementService);
    company = new Company();
    ReflectionTestUtils.setField(company, "id", 9L);
    company.setCode("TENANT");
    lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
    lenient().when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 23));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void getCurrentDealer_bindsByAuthenticatedUserId() {
    UserAccount user = userWithId(100L, "dealer@tenant.com");
    Dealer dealer = dealerWithId(21L);
    dealer.setStatus("ACTIVE");
    authenticate(user, "ROLE_DEALER");
    when(dealerRepository.findAllByCompanyAndPortalUserId(company, 100L))
        .thenReturn(List.of(dealer));

    Dealer resolved = dealerPortalService.getCurrentDealer();

    assertThat(resolved).isSameAs(dealer);
    verify(dealerRepository, never())
        .findAllByCompanyAndPortalUserEmailIgnoreCase(any(), anyString());
  }

  @Test
  void getCurrentRequesterIdentity_exposesAuthenticatedDealerUser() {
    UserAccount user = userWithId(100L, "dealer@tenant.com");
    authenticate(user, "ROLE_DEALER");

    DealerPortalService.RequesterIdentity requesterIdentity =
        dealerPortalService.getCurrentRequesterIdentity();

    assertThat(requesterIdentity.userId()).isEqualTo(100L);
    assertThat(requesterIdentity.email()).isEqualTo("dealer@tenant.com");
  }

  @Test
  void getCurrentRequesterIdentity_fallsBackToAuthenticationNameWhenUserEmailIsBlank() {
    UserAccount user = userWithId(100L, "   ");
    Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
    UserPrincipal principal = new UserPrincipal(user);
    when(authentication.getPrincipal()).thenReturn(principal);
    when(authentication.isAuthenticated()).thenReturn(true);
    when(authentication.getName()).thenReturn("dealer@tenant.com");
    SecurityContextHolder.getContext().setAuthentication(authentication);

    DealerPortalService.RequesterIdentity requesterIdentity =
        dealerPortalService.getCurrentRequesterIdentity();

    assertThat(requesterIdentity.userId()).isEqualTo(100L);
    assertThat(requesterIdentity.email()).isEqualTo("dealer@tenant.com");
  }

  @Test
  void getCurrentDealer_failsClosedWhenUserMapsToMultipleDealers() {
    UserAccount user = userWithId(100L, "dealer@tenant.com");
    authenticate(user, "ROLE_DEALER");
    when(dealerRepository.findAllByCompanyAndPortalUserId(company, 100L))
        .thenReturn(List.of(dealerWithId(21L), dealerWithId(22L)));

    assertThatThrownBy(() -> dealerPortalService.getCurrentDealer())
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Ambiguous dealer mapping");
  }

  @Test
  void getCurrentDealer_deniesInactiveDealerMapping() {
    UserAccount user = userWithId(100L, "dealer@tenant.com");
    Dealer dealer = dealerWithId(21L);
    dealer.setStatus("INACTIVE");
    authenticate(user, "ROLE_DEALER");
    when(dealerRepository.findAllByCompanyAndPortalUserId(company, 100L))
        .thenReturn(List.of(dealer));

    assertThatThrownBy(() -> dealerPortalService.getCurrentDealer())
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("inactive dealer mapping");
  }

  @Test
  void getCurrentDealer_allowsOnHoldDealerMapping() {
    UserAccount user = userWithId(100L, "dealer@tenant.com");
    Dealer dealer = dealerWithId(21L);
    dealer.setStatus("ON_HOLD");
    authenticate(user, "ROLE_DEALER");
    when(dealerRepository.findAllByCompanyAndPortalUserId(company, 100L))
        .thenReturn(List.of(dealer));

    Dealer resolved = dealerPortalService.getCurrentDealer();

    assertThat(resolved).isSameAs(dealer);
  }

  @Test
  void getCurrentDealer_fallsBackToEmailWhenPrincipalUserIdMissing() {
    UserAccount user = new UserAccount("dealer@tenant.com", "hash", "Dealer");
    Dealer dealer = dealerWithId(33L);
    dealer.setStatus(" active ");
    authenticate(user, "ROLE_DEALER");
    when(dealerRepository.findAllByCompanyAndPortalUserEmailIgnoreCase(
            company, "dealer@tenant.com"))
        .thenReturn(List.of(dealer));

    Dealer resolved = dealerPortalService.getCurrentDealer();

    assertThat(resolved).isSameAs(dealer);
    verify(dealerRepository, never()).findAllByCompanyAndPortalUserId(any(), any());
  }

  @Test
  void getCurrentDealer_fallsBackToEmailWhenPrincipalUserIdMappingIsMissing() {
    UserAccount user = userWithId(100L, "dealer@tenant.com");
    Dealer dealer = dealerWithId(33L);
    dealer.setStatus("ACTIVE");
    authenticate(user, "ROLE_DEALER");
    when(dealerRepository.findAllByCompanyAndPortalUserId(company, 100L)).thenReturn(List.of());
    when(dealerRepository.findAllByCompanyAndPortalUserEmailIgnoreCase(
            company, "dealer@tenant.com"))
        .thenReturn(List.of(dealer));

    Dealer resolved = dealerPortalService.getCurrentDealer();

    assertThat(resolved).isSameAs(dealer);
  }

  @Test
  void getCurrentDealer_deniesInactiveDealerOnEmailFallback() {
    UserAccount user = userWithId(100L, "dealer@tenant.com");
    Dealer dealer = dealerWithId(33L);
    dealer.setStatus("INACTIVE");
    authenticate(user, "ROLE_DEALER");
    when(dealerRepository.findAllByCompanyAndPortalUserId(company, 100L)).thenReturn(List.of());
    when(dealerRepository.findAllByCompanyAndPortalUserEmailIgnoreCase(
            company, "dealer@tenant.com"))
        .thenReturn(List.of(dealer));

    assertThatThrownBy(() -> dealerPortalService.getCurrentDealer())
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("inactive dealer mapping");
  }

  @Test
  void getCurrentDealer_allowsOnHoldDealerOnEmailFallback() {
    UserAccount user = userWithId(100L, "dealer@tenant.com");
    Dealer dealer = dealerWithId(33L);
    dealer.setStatus(" on_hold ");
    authenticate(user, "ROLE_DEALER");
    when(dealerRepository.findAllByCompanyAndPortalUserId(company, 100L)).thenReturn(List.of());
    when(dealerRepository.findAllByCompanyAndPortalUserEmailIgnoreCase(
            company, "dealer@tenant.com"))
        .thenReturn(List.of(dealer));

    Dealer resolved = dealerPortalService.getCurrentDealer();

    assertThat(resolved).isSameAs(dealer);
  }

  @Test
  void requireActivePortalDealer_rejectsNullDealer() {
    assertThatThrownBy(
            () ->
                com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                    dealerPortalService, "requireActivePortalDealer", new Object[] {null}))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("mapping missing");
  }

  @Test
  void getCurrentDealer_failsClosedWhenEmailFallbackHasNoDealerMapping() {
    UserAccount user = new UserAccount("dealer@tenant.com", "hash", "Dealer");
    authenticate(user, "ROLE_DEALER");
    when(dealerRepository.findAllByCompanyAndPortalUserEmailIgnoreCase(
            company, "dealer@tenant.com"))
        .thenReturn(List.of());

    assertThatThrownBy(() -> dealerPortalService.getCurrentDealer())
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("mapping missing");
  }

  @Test
  void getCurrentDealer_failsClosedWhenEmailFallbackMapsToMultipleDealers() {
    UserAccount user = new UserAccount("dealer@tenant.com", "hash", "Dealer");
    authenticate(user, "ROLE_DEALER");
    when(dealerRepository.findAllByCompanyAndPortalUserEmailIgnoreCase(
            company, "dealer@tenant.com"))
        .thenReturn(List.of(dealerWithId(33L), dealerWithId(34L)));

    assertThatThrownBy(() -> dealerPortalService.getCurrentDealer())
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Ambiguous dealer mapping");
  }

  @Test
  void getCurrentDealer_failsClosedWhenUserIdAndEmailMappingsAreBothMissing() {
    UserAccount user = userWithId(100L, "dealer@tenant.com");
    authenticate(user, "ROLE_DEALER");
    when(dealerRepository.findAllByCompanyAndPortalUserId(company, 100L)).thenReturn(List.of());
    when(dealerRepository.findAllByCompanyAndPortalUserEmailIgnoreCase(
            company, "dealer@tenant.com"))
        .thenReturn(List.of());

    assertThatThrownBy(() -> dealerPortalService.getCurrentDealer())
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("mapping missing for authenticated principal");
    verify(dealerRepository)
        .findAllByCompanyAndPortalUserEmailIgnoreCase(company, "dealer@tenant.com");
  }

  @Test
  void verifyDealerAccess_rejectsCrossDealerReadForDealerRole() {
    UserAccount user = userWithId(100L, "dealer@tenant.com");
    authenticate(user, "ROLE_DEALER");
    when(dealerRepository.findAllByCompanyAndPortalUserId(company, 100L))
        .thenReturn(List.of(dealerWithId(45L)));

    assertThatThrownBy(() -> dealerPortalService.verifyDealerAccess(99L))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Access denied");
  }

  @Test
  void verifyDealerAccess_skipsDealerBoundaryForNonDealerRoles() {
    UserAccount user = userWithId(100L, "admin@tenant.com");
    authenticate(user, "ROLE_ADMIN");

    dealerPortalService.verifyDealerAccess(99L);

    verify(dealerRepository, never()).findAllByCompanyAndPortalUserId(any(), any());
    verify(dealerRepository, never())
        .findAllByCompanyAndPortalUserEmailIgnoreCase(any(), anyString());
  }

  @Test
  void getCurrentDealer_deniesWhenNoAuthenticationPresent() {
    SecurityContextHolder.clearContext();

    assertThatThrownBy(() -> dealerPortalService.getCurrentDealer())
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("No authenticated user");
  }

  @Test
  void getCurrentDealer_deniesWhenAuthenticationIsPresentButNotAuthenticated() {
    var auth =
        new UsernamePasswordAuthenticationToken(
            "dealer@tenant.com", "token", List.of(new SimpleGrantedAuthority("ROLE_DEALER")));
    auth.setAuthenticated(false);
    SecurityContextHolder.getContext().setAuthentication(auth);

    assertThatThrownBy(() -> dealerPortalService.getCurrentDealer())
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("No authenticated user");
  }

  @Test
  void getCurrentDealer_deniesWhenPrincipalIdentityIsBlank() {
    var auth =
        new UsernamePasswordAuthenticationToken(
            "   ", "token", List.of(new SimpleGrantedAuthority("ROLE_DEALER")));
    SecurityContextHolder.getContext().setAuthentication(auth);

    assertThatThrownBy(() -> dealerPortalService.getCurrentDealer())
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("No authenticated user identity");
  }

  @Test
  void getMyDashboard_includesCreditStatusAndPendingExposure() {
    UserAccount user = userWithId(100L, "dealer@tenant.com");
    Dealer dealer = dealerWithId(21L);
    dealer.setName("Dealer Name");
    dealer.setCode("DLR-21");
    dealer.setCreditLimit(new BigDecimal("1000"));
    dealer.setCompany(company);

    authenticate(user, "ROLE_DEALER");
    when(dealerRepository.findAllByCompanyAndPortalUserId(company, 100L))
        .thenReturn(List.of(dealer));
    when(dealerLedgerService.currentBalance(21L)).thenReturn(new BigDecimal("550"));

    when(statementService.dealerOpenInvoiceCount(eq(dealer), any(LocalDate.class))).thenReturn(1L);

    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(
            eq(company), eq(dealer), any(), isNull()))
        .thenReturn(new BigDecimal("300"));
    when(salesOrderRepository.countPendingCreditExposureByCompanyAndDealer(
            eq(company), eq(dealer), any(), isNull()))
        .thenReturn(2L);
    when(statementService.dealerAging(
            eq(dealer), any(LocalDate.class), eq("0-0,1-30,31-60,61-90,91")))
        .thenReturn(
            new AgingSummaryResponse(
                21L,
                "Dealer Name",
                new BigDecimal("550"),
                List.of(new AgingBucketDto("1-30 days", 1, 30, new BigDecimal("550")))));
    when(statementService.dealerOverdueInvoices(eq(dealer), any(LocalDate.class)))
        .thenReturn(List.of());

    Map<String, Object> dashboard = dealerPortalService.getMyDashboard();

    assertThat(dashboard.get("pendingOrderCount")).isEqualTo(2L);
    assertThat(dashboard.get("pendingOrderExposure")).isEqualTo(new BigDecimal("300"));
    assertThat(dashboard.get("creditUsed")).isEqualTo(new BigDecimal("850"));
    assertThat(dashboard.get("creditStatus")).isEqualTo("NEAR_LIMIT");
    assertThat(dashboard.get("outstandingBalance")).isEqualTo(new BigDecimal("550"));
    assertThat(dashboard.get("creditAvailable")).isEqualTo(new BigDecimal("150"));
  }

  @Test
  void getAgingForDealer_defaultsMissingLedgerOutstandingAndCreditLimitToZero() {
    UserAccount user = userWithId(100L, "dealer@tenant.com");
    Dealer dealer = dealerWithId(21L);
    dealer.setName("Dealer Name");
    dealer.setCompany(company);
    dealer.setCreditLimit(null);

    authenticate(user, "ROLE_DEALER");
    when(dealerRepository.findAllByCompanyAndPortalUserId(company, 100L))
        .thenReturn(List.of(dealer));
    when(dealerRepository.findByCompanyAndId(company, 21L))
        .thenReturn(java.util.Optional.of(dealer));
    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(
            eq(company), eq(dealer), any(), isNull()))
        .thenReturn(new BigDecimal("50"));
    when(salesOrderRepository.countPendingCreditExposureByCompanyAndDealer(
            eq(company), eq(dealer), any(), isNull()))
        .thenReturn(1L);
    when(statementService.dealerAging(
            eq(dealer), any(LocalDate.class), eq("0-0,1-30,31-60,61-90,91")))
        .thenReturn(new AgingSummaryResponse(21L, "Dealer Name", null, null));
    when(statementService.dealerOverdueInvoices(eq(dealer), any(LocalDate.class)))
        .thenReturn(List.of());

    Map<String, Object> aging = dealerPortalService.getAgingForDealer(21L);

    assertThat(aging.get("creditLimit")).isEqualTo(BigDecimal.ZERO);
    assertThat(aging.get("totalOutstanding")).isEqualTo(BigDecimal.ZERO);
    assertThat(aging.get("pendingOrderExposure")).isEqualTo(new BigDecimal("50"));
    assertThat(aging.get("creditUsed")).isEqualTo(new BigDecimal("50"));
    assertThat(aging.get("availableCredit")).isEqualTo(BigDecimal.ZERO);
    assertThat(aging.get("overdueInvoices")).isEqualTo(List.of());
    assertThat((Map<String, Object>) aging.get("agingBuckets"))
        .containsEntry("current", BigDecimal.ZERO)
        .containsEntry("90+ days", BigDecimal.ZERO);
  }

  @Test
  void getMyDashboard_mapsAllLedgerBucketsAndCountsOnlyPositiveOutstandingInvoices() {
    UserAccount user = userWithId(100L, "dealer@tenant.com");
    Dealer dealer = dealerWithId(21L);
    dealer.setName("Dealer Name");
    dealer.setCode("DLR-21");
    dealer.setCreditLimit(new BigDecimal("1000"));
    dealer.setCompany(company);

    authenticate(user, "ROLE_DEALER");
    when(dealerRepository.findAllByCompanyAndPortalUserId(company, 100L))
        .thenReturn(List.of(dealer));
    when(dealerRepository.findByCompanyAndId(company, 21L))
        .thenReturn(java.util.Optional.of(dealer));
    when(dealerLedgerService.currentBalance(21L)).thenReturn(new BigDecimal("900"));

    when(statementService.dealerOpenInvoiceCount(eq(dealer), any(LocalDate.class))).thenReturn(1L);

    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(
            eq(company), eq(dealer), any(), isNull()))
        .thenReturn(new BigDecimal("200"));
    when(salesOrderRepository.countPendingCreditExposureByCompanyAndDealer(
            eq(company), eq(dealer), any(), isNull()))
        .thenReturn(2L);
    when(statementService.dealerAging(
            eq(dealer), any(LocalDate.class), eq("0-0,1-30,31-60,61-90,91")))
        .thenReturn(
            new AgingSummaryResponse(
                21L,
                "Dealer Name",
                new BigDecimal("900"),
                Arrays.asList(
                    new AgingBucketDto("0-0 days", 0, 0, new BigDecimal("50")),
                    new AgingBucketDto("1-30 days", 1, 30, new BigDecimal("100")),
                    new AgingBucketDto("31-60 days", 31, 60, new BigDecimal("200")),
                    new AgingBucketDto("61-90 days", 61, 90, new BigDecimal("250")),
                    new AgingBucketDto("91+ days", 91, null, new BigDecimal("300")),
                    new AgingBucketDto("Credit Balance", 0, 0, new BigDecimal("-40")),
                    null,
                    new AgingBucketDto("1-30 days", 1, 30, null),
                    new AgingBucketDto("unknown", 10, 20, new BigDecimal("999")))));
    when(statementService.dealerOverdueInvoices(eq(dealer), any(LocalDate.class)))
        .thenReturn(
            Arrays.asList(
                null,
                new OverdueInvoiceDto(
                    "INV-001",
                    LocalDate.of(2026, 1, 2),
                    LocalDate.of(2026, 2, 1),
                    22L,
                    new BigDecimal("100"))));

    Map<String, Object> dashboard = dealerPortalService.getMyDashboard();
    Map<String, Object> aging = dealerPortalService.getAgingForDealer(21L);

    assertThat(dashboard.get("pendingInvoices")).isEqualTo(1L);
    assertThat(dashboard.get("creditUsed")).isEqualTo(new BigDecimal("1100"));
    assertThat(dashboard.get("creditStatus")).isEqualTo("OVER_LIMIT");
    assertThat((Map<String, Object>) dashboard.get("agingBuckets"))
        .containsEntry("current", new BigDecimal("50"))
        .containsEntry("30days", new BigDecimal("100"))
        .containsEntry("1-30 days", new BigDecimal("100"))
        .containsEntry("60days", new BigDecimal("200"))
        .containsEntry("31-60 days", new BigDecimal("200"))
        .containsEntry("90days", new BigDecimal("250"))
        .containsEntry("61-90 days", new BigDecimal("250"))
        .containsEntry("over90", new BigDecimal("300"))
        .containsEntry("90+ days", new BigDecimal("300"));
    assertThat((List<Map<String, Object>>) aging.get("overdueInvoices"))
        .singleElement()
        .satisfies(
            entry ->
                assertThat((Map<String, Object>) entry)
                    .containsEntry("invoiceNumber", "INV-001")
                    .containsEntry("issueDate", LocalDate.of(2026, 1, 2))
                    .containsEntry("daysOverdue", 22L)
                    .containsEntry("outstandingAmount", new BigDecimal("100")));
  }

  @Test
  void getMyDashboard_countsPendingInvoicesFromLedgerNetting() {
    UserAccount user = userWithId(100L, "dealer@tenant.com");
    Dealer dealer = dealerWithId(21L);
    dealer.setName("Dealer Name");
    dealer.setCode("DLR-21");
    dealer.setCreditLimit(new BigDecimal("1000"));
    dealer.setCompany(company);

    authenticate(user, "ROLE_DEALER");
    when(dealerRepository.findAllByCompanyAndPortalUserId(company, 100L))
        .thenReturn(List.of(dealer));
    when(dealerLedgerService.currentBalance(21L)).thenReturn(BigDecimal.ZERO);
    when(statementService.dealerOpenInvoiceCount(eq(dealer), any(LocalDate.class))).thenReturn(0L);
    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(
            eq(company), eq(dealer), any(), isNull()))
        .thenReturn(BigDecimal.ZERO);
    when(salesOrderRepository.countPendingCreditExposureByCompanyAndDealer(
            eq(company), eq(dealer), any(), isNull()))
        .thenReturn(0L);
    when(statementService.dealerAging(
            eq(dealer), any(LocalDate.class), eq("0-0,1-30,31-60,61-90,91")))
        .thenReturn(new AgingSummaryResponse(21L, "Dealer Name", BigDecimal.ZERO, List.of()));
    when(statementService.dealerOverdueInvoices(eq(dealer), any(LocalDate.class)))
        .thenReturn(List.of());

    Map<String, Object> dashboard = dealerPortalService.getMyDashboard();

    assertThat(dashboard.get("pendingInvoices")).isEqualTo(0L);
    verify(invoiceRepository, never()).findByCompanyAndDealerOrderByIssueDateDesc(company, dealer);
  }

  @Test
  void overdueInvoicePayloadReturnsEmptyListForNullInput() {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> payload =
        (List<Map<String, Object>>)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                dealerPortalService, "toOverdueInvoicePayload", new Object[] {null});

    assertThat(payload).isEqualTo(List.of());
  }

  @Test
  void portalAgingBucketsReturnDefaultsWhenResponseIsNull() {
    @SuppressWarnings("unchecked")
    Map<String, Object> buckets =
        (Map<String, Object>)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                dealerPortalService, "toPortalAgingBuckets", new Object[] {null});

    assertThat(buckets)
        .containsEntry("current", BigDecimal.ZERO)
        .containsEntry("90+ days", BigDecimal.ZERO);
  }

  @Test
  void portalAgingBucketsIgnoreSyntheticCreditBalanceBucket() {
    @SuppressWarnings("unchecked")
    Map<String, Object> buckets =
        (Map<String, Object>)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                dealerPortalService,
                "toPortalAgingBuckets",
                new AgingSummaryResponse(
                    21L,
                    "Dealer Name",
                    BigDecimal.ZERO,
                    List.of(new AgingBucketDto("Credit Balance", 0, 0, new BigDecimal("-25")))));

    assertThat(buckets).containsEntry("current", BigDecimal.ZERO);
  }

  @Test
  void resolveCreditStatusReturnsWithinLimitBelowThreshold() {
    String status =
        (String)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                dealerPortalService,
                "resolveCreditStatus",
                new BigDecimal("1000"),
                new BigDecimal("200"));

    assertThat(status).isEqualTo("WITHIN_LIMIT");
  }

  @Test
  void agingViewClampsNegativeLedgerBalanceWhenComputingCreditUsage() {
    UserAccount user = userWithId(100L, "dealer@tenant.com");
    Dealer dealer = dealerWithId(21L);
    dealer.setName("Dealer Name");
    dealer.setCompany(company);
    dealer.setCreditLimit(new BigDecimal("1000"));

    authenticate(user, "ROLE_DEALER");
    when(dealerRepository.findAllByCompanyAndPortalUserId(company, 100L))
        .thenReturn(List.of(dealer));
    when(dealerRepository.findByCompanyAndId(company, 21L))
        .thenReturn(java.util.Optional.of(dealer));
    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(
            eq(company), eq(dealer), any(), isNull()))
        .thenReturn(new BigDecimal("50"));
    when(salesOrderRepository.countPendingCreditExposureByCompanyAndDealer(
            eq(company), eq(dealer), any(), isNull()))
        .thenReturn(1L);
    when(statementService.dealerAging(
            eq(dealer), any(LocalDate.class), eq("0-0,1-30,31-60,61-90,91")))
        .thenReturn(
            new AgingSummaryResponse(21L, "Dealer Name", new BigDecimal("-125"), List.of()));
    when(statementService.dealerOverdueInvoices(eq(dealer), any(LocalDate.class)))
        .thenReturn(List.of());

    Map<String, Object> aging = dealerPortalService.getAgingForDealer(21L);

    assertThat(aging.get("totalOutstanding")).isEqualTo(new BigDecimal("-125"));
    assertThat(aging.get("creditUsed")).isEqualTo(new BigDecimal("50"));
    assertThat(aging.get("availableCredit")).isEqualTo(new BigDecimal("950"));
  }

  @Test
  void contributesPendingCreditExposure_excludesCashOrders() {
    SalesOrder cashOrder = new SalesOrder();
    ReflectionTestUtils.setField(cashOrder, "id", 88L);
    cashOrder.setStatus("CONFIRMED");
    cashOrder.setPaymentMode("CASH");

    Boolean contributes =
        (Boolean)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                dealerPortalService, "contributesPendingCreditExposure", cashOrder, Set.<Long>of());

    assertThat(contributes).isFalse();
  }

  private void authenticate(UserAccount user, String... authorities) {
    UserPrincipal principal = new UserPrincipal(user);
    var grantedAuthorities =
        java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
    var auth = new UsernamePasswordAuthenticationToken(principal, "token", grantedAuthorities);
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  private UserAccount userWithId(Long id, String email) {
    UserAccount user = new UserAccount(email, "hash", "Dealer");
    ReflectionTestUtils.setField(user, "id", id);
    return user;
  }

  private Dealer dealerWithId(Long id) {
    Dealer dealer = new Dealer();
    dealer.setCompany(company);
    dealer.setStatus("ACTIVE");
    ReflectionTestUtils.setField(dealer, "id", id);
    return dealer;
  }
}
