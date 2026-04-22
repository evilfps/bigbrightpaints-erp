package com.bigbrightpaints.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.GstRegistrationType;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingBucketDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingSummaryResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.OverdueInvoiceDto;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.ScopedAccountBootstrapService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerPaymentTerms;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.dto.CreateDealerRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DealerCreditExposureView;
import com.bigbrightpaints.erp.modules.sales.dto.DealerResponse;

@Tag("critical")
@ExtendWith(MockitoExtension.class)
class DealerServiceTest {

  @Mock private DealerRepository dealerRepository;
  @Mock private CompanyContextService companyContextService;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private RoleService roleService;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private EmailService emailService;
  @Mock private AuthScopeService authScopeService;
  @Mock private AccountRepository accountRepository;

  @Mock
  private com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService
      dealerLedgerService;

  @Mock private StatementService statementService;
  @Mock private SalesOrderRepository salesOrderRepository;
  @Mock private CompanyClock companyClock;

  private DealerService dealerService;
  private Company company;
  private ScopedAccountBootstrapService scopedAccountBootstrapService;

  @BeforeEach
  void setUp() {
    scopedAccountBootstrapService =
        new ScopedAccountBootstrapService(
            userAccountRepository, passwordEncoder, emailService, authScopeService);
    dealerService =
        new DealerService(
            dealerRepository,
            companyContextService,
            userAccountRepository,
            roleService,
            scopedAccountBootstrapService,
            accountRepository,
            dealerLedgerService,
            statementService,
            salesOrderRepository,
            companyClock);

    company = new Company();
    ReflectionTestUtils.setField(company, "id", 1L);
    company.setCode("TEST");

    lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
    lenient()
        .when(authScopeService.requireScopeCode(anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0, String.class).trim().toUpperCase());
    lenient()
        .when(
            dealerRepository.findAllByCompanyAndPortalUserEmailIgnoreCase(eq(company), anyString()))
        .thenReturn(List.of());
    lenient()
        .when(dealerRepository.findByCompanyAndEmailIgnoreCase(eq(company), anyString()))
        .thenReturn(Optional.empty());
    lenient()
        .when(dealerRepository.findByCompanyAndCodeIgnoreCase(eq(company), anyString()))
        .thenReturn(Optional.empty());
    lenient()
        .when(dealerRepository.save(any(Dealer.class)))
        .thenAnswer(
            invocation -> {
              Dealer dealer = invocation.getArgument(0);
              if (dealer.getId() == null) {
                ReflectionTestUtils.setField(dealer, "id", 99L);
              }
              return dealer;
            });
    lenient()
        .when(
            userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
                anyString(), anyString()))
        .thenReturn(Optional.empty());
    lenient()
        .when(
            userAccountRepository.existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
                anyString(), anyString()))
        .thenReturn(false);
    lenient()
        .when(userAccountRepository.save(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(passwordEncoder.encode(anyString())).thenReturn("encoded");

    Role dealerRole = new Role();
    dealerRole.setName("ROLE_DEALER");
    lenient().when(roleService.ensureRoleExists("ROLE_DEALER")).thenReturn(dealerRole);

    lenient()
        .when(accountRepository.findByCompanyAndCodeIgnoreCase(eq(company), anyString()))
        .thenReturn(Optional.empty());
    lenient()
        .when(accountRepository.save(any(Account.class)))
        .thenAnswer(
            invocation -> {
              Account account = invocation.getArgument(0);
              if (account.getId() == null) {
                ReflectionTestUtils.setField(account, "id", 123L);
              }
              return account;
            });
    lenient()
        .when(
            salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(
                eq(company), any(Dealer.class), any(), eq(null)))
        .thenReturn(BigDecimal.ZERO);
    lenient().when(companyClock.now(company)).thenReturn(Instant.parse("2026-02-23T10:00:00Z"));
  }

  @Test
  void createDealer_sendsScopedCredentialsEmailForNewPortalUser() {
    dealerService.createDealer(request());

    verify(emailService)
        .sendUserCredentialsEmailRequired(
            eq("dealer@example.com"), eq("Test Dealer"), anyString(), eq("TEST"));
  }

  @Test
  void createDealer_reusesExistingScopedPortalUserWithoutSendingNewCredentials() {
    UserAccount existingPortalUser =
        new UserAccount("dealer@example.com", "TEST", "hash", "Test Dealer");
    existingPortalUser.setCompany(company);
    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "dealer@example.com", "TEST"))
        .thenReturn(Optional.of(existingPortalUser));

    dealerService.createDealer(request());

    verify(emailService, never())
        .sendUserCredentialsEmailRequired(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void createDealer_linksReceivableAccountUnderArControlWhenPresent() {
    Account arControl = new Account();
    arControl.setType(AccountType.ASSET);
    arControl.setCode("AR");
    when(accountRepository.findByCompanyAndCodeIgnoreCase(eq(company), eq("AR")))
        .thenReturn(Optional.of(arControl));

    dealerService.createDealer(request());

    ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
    verify(accountRepository).save(accountCaptor.capture());
    assertThat(accountCaptor.getValue().getParent()).isSameAs(arControl);
  }

  @Test
  void createDealer_reusesExistingDealerByPortalEmailMapping() {
    Dealer existing = new Dealer();
    existing.setCompany(company);
    existing.setCode("PORTAL-MAPPED");
    existing.setName("Portal Dealer");
    existing.setStatus("SUSPENDED");
    ReflectionTestUtils.setField(existing, "id", 88L);

    when(dealerRepository.findAllByCompanyAndPortalUserEmailIgnoreCase(
            eq(company), eq("dealer@example.com")))
        .thenReturn(List.of(existing));

    DealerResponse response = dealerService.createDealer(request());

    assertThat(response.id()).isEqualTo(88L);
    assertThat(response.code()).isEqualTo("PORTAL-MAPPED");
    assertThat(response.arAccountId()).isNotNull();
    assertThat(response.receivableAccountCode()).isNotBlank();
    ArgumentCaptor<Dealer> dealerCaptor = ArgumentCaptor.forClass(Dealer.class);
    verify(dealerRepository, atLeastOnce()).save(dealerCaptor.capture());
    Dealer saved = dealerCaptor.getAllValues().get(dealerCaptor.getAllValues().size() - 1);
    assertThat(saved.getStatus()).isEqualTo("SUSPENDED");
  }

  @Test
  void createDealer_rejectsAmbiguousPortalMappingsFailClosed() {
    Dealer first = new Dealer();
    Dealer second = new Dealer();
    when(dealerRepository.findAllByCompanyAndPortalUserEmailIgnoreCase(
            eq(company), eq("dealer@example.com")))
        .thenReturn(List.of(first, second));

    assertThatThrownBy(() -> dealerService.createDealer(request()))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Dealer onboarding is ambiguous for this portal user")
        .extracting(ex -> ((ApplicationException) ex).getErrorCode())
        .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
  }

  @Test
  void createDealer_mapsGstStateTermsAndRegion() {
    dealerService.createDealer(
        new CreateDealerRequest(
            "Test Dealer",
            "Test Dealer Co",
            "dealer@example.com",
            "9999999999",
            "Address",
            new BigDecimal("1000.00"),
            "29ABCDE1234F1Z5",
            "ka",
            GstRegistrationType.REGULAR,
            DealerPaymentTerms.NET_60,
            "south"));

    ArgumentCaptor<Dealer> dealerCaptor = ArgumentCaptor.forClass(Dealer.class);
    verify(dealerRepository, atLeastOnce()).save(dealerCaptor.capture());
    Dealer saved = dealerCaptor.getAllValues().get(dealerCaptor.getAllValues().size() - 1);
    assertThat(saved.getGstNumber()).isEqualTo("29ABCDE1234F1Z5");
    assertThat(saved.getStateCode()).isEqualTo("KA");
    assertThat(saved.getGstRegistrationType()).isEqualTo(GstRegistrationType.REGULAR);
    assertThat(saved.getPaymentTerms()).isEqualTo(DealerPaymentTerms.NET_60);
    assertThat(saved.getRegion()).isEqualTo("SOUTH");
  }

  @Test
  void search_filtersByComputedCreditStatus() {
    Dealer within = dealer("D-WITHIN", new BigDecimal("1000"), "NORTH");
    Dealer near = dealer("D-NEAR", new BigDecimal("1000"), "NORTH");
    when(dealerRepository.searchFiltered(eq(company), eq(""), eq(null), eq("NORTH"), any()))
        .thenReturn(List.of(within, near));
    when(dealerLedgerService.currentBalances(List.of(1L, 2L)))
        .thenReturn(
            java.util.Map.of(
                1L, new BigDecimal("200"),
                2L, new BigDecimal("850")));
    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealerIds(
            eq(company), eq(List.of(1L, 2L)), any()))
        .thenReturn(
            List.of(
                new DealerCreditExposureView(1L, new BigDecimal("100")),
                new DealerCreditExposureView(2L, BigDecimal.ZERO)));

    var results = dealerService.search("", null, "north", "NEAR_LIMIT");

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().code()).isEqualTo("D-NEAR");
    assertThat(results.getFirst().creditStatus()).isEqualTo("NEAR_LIMIT");
  }

  @Test
  void listDealers_usesBatchPendingExposureMapForCreditStatus() {
    Dealer within = dealer("D-WITHIN", new BigDecimal("1000"), "NORTH");
    Dealer near = dealer("D-NEAR", new BigDecimal("1000"), "NORTH");
    when(dealerRepository.findByCompanyAndStatusIgnoreCaseOrderByNameAsc(company, "ACTIVE"))
        .thenReturn(List.of(within, near));
    when(dealerLedgerService.currentBalances(List.of(1L, 2L)))
        .thenReturn(
            java.util.Map.of(
                1L, new BigDecimal("200"),
                2L, new BigDecimal("850")));
    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealerIds(
            eq(company), eq(List.of(1L, 2L)), any()))
        .thenReturn(
            List.of(
                new DealerCreditExposureView(1L, new BigDecimal("100")),
                new DealerCreditExposureView(2L, BigDecimal.ZERO)));

    var results = dealerService.listDealers();

    assertThat(results).hasSize(2);
    assertThat(results)
        .extracting(com.bigbrightpaints.erp.modules.sales.dto.DealerResponse::creditStatus)
        .containsExactly("WITHIN_LIMIT", "NEAR_LIMIT");
  }

  @Test
  void listDealers_defaultsMissingBalanceAndExposureToZero() {
    Dealer dealer = dealer("D-ZERO", new BigDecimal("1000"), "NORTH");
    when(dealerRepository.findByCompanyAndStatusIgnoreCaseOrderByNameAsc(company, "ACTIVE"))
        .thenReturn(List.of(dealer));
    when(dealerLedgerService.currentBalances(List.of(99L))).thenReturn(Map.of());
    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealerIds(
            eq(company), eq(List.of(99L)), any()))
        .thenReturn(List.of());

    var results = dealerService.listDealers();

    assertThat(results)
        .singleElement()
        .satisfies(result -> assertThat(result.creditStatus()).isEqualTo("WITHIN_LIMIT"));
  }

  @Test
  void listDealers_allStatusUsesDirectoryQueryAndPagination() {
    Dealer active = dealer("D-ACTIVE", new BigDecimal("1000"), "NORTH");
    Dealer onHold = dealer("D-HOLD", new BigDecimal("1000"), "SOUTH");
    PageRequest pageRequest =
        PageRequest.of(
            1,
            1,
            org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Order.asc("name"),
                org.springframework.data.domain.Sort.Order.asc("id")));
    when(dealerRepository.findByCompany(eq(company), eq(pageRequest)))
        .thenReturn(List.of(active, onHold));
    when(dealerLedgerService.currentBalances(List.of(3L, 4L)))
        .thenReturn(Map.of(3L, BigDecimal.ZERO, 4L, BigDecimal.ZERO));
    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealerIds(
            eq(company), eq(List.of(3L, 4L)), any()))
        .thenReturn(List.of());

    var results = dealerService.listDealers("all", 1, 1);

    assertThat(results).hasSize(2);
    verify(dealerRepository).findByCompany(eq(company), eq(pageRequest));
  }

  @Test
  void listDealers_blankStatusFallsBackToActiveDirectoryQuery() {
    Dealer active = dealer("D-ACTIVE", new BigDecimal("1000"), "NORTH");
    when(dealerRepository.findByCompanyAndStatusIgnoreCaseOrderByNameAsc(company, "ACTIVE"))
        .thenReturn(List.of(active));
    when(dealerLedgerService.currentBalances(List.of(3L))).thenReturn(Map.of(3L, BigDecimal.ZERO));
    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealerIds(
            eq(company), eq(List.of(3L)), any()))
        .thenReturn(List.of());

    var results = dealerService.listDealers("   ", null, null);

    assertThat(results).singleElement().extracting(result -> result.code()).isEqualTo("D-ACTIVE");
    verify(dealerRepository).findByCompanyAndStatusIgnoreCaseOrderByNameAsc(company, "ACTIVE");
  }

  @Test
  void listDealers_statusPaginationNormalizesStatusAndCapsPageSize() {
    Dealer onHold = dealer("D-HOLD", new BigDecimal("1000"), "SOUTH");
    PageRequest pageRequest =
        PageRequest.of(
            0,
            200,
            org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Order.asc("name"),
                org.springframework.data.domain.Sort.Order.asc("id")));
    when(dealerRepository.findByCompanyAndStatusIgnoreCase(
            eq(company), eq("ON_HOLD"), eq(pageRequest)))
        .thenReturn(List.of(onHold));
    when(dealerLedgerService.currentBalances(List.of(4L))).thenReturn(Map.of(4L, BigDecimal.ZERO));
    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealerIds(
            eq(company), eq(List.of(4L)), any()))
        .thenReturn(List.of());

    var results = dealerService.listDealers(" on_hold ", 0, 999);

    assertThat(results).singleElement().extracting(result -> result.code()).isEqualTo("D-HOLD");
    verify(dealerRepository)
        .findByCompanyAndStatusIgnoreCase(eq(company), eq("ON_HOLD"), eq(pageRequest));
  }

  @Test
  void getDealer_returnsDealerPayloadWhenDealerExists() {
    Dealer dealer = dealer("D-DETAIL", new BigDecimal("1000"), "WEST");
    UserAccount portalUser = new UserAccount("dealer-detail@bbp.com", "TEST", "hash", "Dealer");
    dealer.setPortalUser(portalUser);
    Account receivableAccount = new Account();
    ReflectionTestUtils.setField(receivableAccount, "id", 321L);
    receivableAccount.setCode("AR-D-DETAIL");
    dealer.setReceivableAccount(receivableAccount);
    when(dealerRepository.findByCompanyAndId(company, 99L)).thenReturn(Optional.of(dealer));
    when(dealerLedgerService.currentBalance(99L)).thenReturn(new BigDecimal("250"));
    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(
            eq(company), eq(dealer), any(), eq(null)))
        .thenReturn(new BigDecimal("100"));

    var response = dealerService.getDealer(99L);

    assertThat(response.id()).isEqualTo(99L);
    assertThat(response.portalEmail()).isEqualTo("dealer-detail@bbp.com");
    assertThat(response.receivableAccountCode()).isEqualTo("AR-D-DETAIL");
    assertThat(response.outstandingBalance()).isEqualByComparingTo("250");
    assertThat(response.creditStatus()).isEqualTo("WITHIN_LIMIT");
  }

  @Test
  void getDealer_defaultsPortalEmailAndOutstandingBalanceWhenDataMissing() {
    Dealer dealer = dealer("D-DEFAULTS", new BigDecimal("1000"), "WEST");
    dealer.setPortalUser(null);
    dealer.setReceivableAccount(null);
    when(dealerRepository.findByCompanyAndId(company, 99L)).thenReturn(Optional.of(dealer));
    when(dealerLedgerService.currentBalance(99L)).thenReturn(null);
    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(
            eq(company), eq(dealer), any(), eq(null)))
        .thenReturn(BigDecimal.ZERO);

    var response = dealerService.getDealer(99L);

    assertThat(response.id()).isEqualTo(99L);
    assertThat(response.portalEmail()).isNull();
    assertThat(response.outstandingBalance()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void getDealer_returnsNotFoundWhenDealerDoesNotExist() {
    when(dealerRepository.findByCompanyAndId(company, 404L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> dealerService.getDealer(404L))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_ENTITY_NOT_FOUND);
              assertThat(ex.getMessage()).isEqualTo("Dealer not found");
            });
  }

  @Test
  void creditUtilization_includesPendingExposureAndCreditStatus() {
    Dealer dealer = dealer("D-CREDIT", new BigDecimal("1000"), "WEST");
    when(dealerRepository.findByCompanyAndId(company, 1L)).thenReturn(Optional.of(dealer));
    when(dealerLedgerService.currentBalance(1L)).thenReturn(new BigDecimal("650"));
    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(
            eq(company), eq(dealer), any(), eq(null)))
        .thenReturn(new BigDecimal("250"));

    var payload = dealerService.creditUtilization(1L);

    assertThat(payload)
        .containsEntry("dealerId", 1L)
        .containsEntry("dealerName", "D-CREDIT Name")
        .doesNotContainKeys(
            "creditLimit",
            "outstandingAmount",
            "pendingOrderExposure",
            "creditUsed",
            "availableCredit");
    assertThat(payload.get("creditStatus")).isEqualTo("NEAR_LIMIT");
  }

  @Test
  void resolvePendingOrderExposureMap_returnsEmptyForMissingInputs() {
    @SuppressWarnings("unchecked")
    Map<Long, BigDecimal> noCompany =
        (Map<Long, BigDecimal>)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                dealerService, "resolvePendingOrderExposureMap", null, List.of(1L));
    @SuppressWarnings("unchecked")
    Map<Long, BigDecimal> nullDealerIds =
        (Map<Long, BigDecimal>)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                dealerService, "resolvePendingOrderExposureMap", company, null);
    @SuppressWarnings("unchecked")
    Map<Long, BigDecimal> noDealerIds =
        (Map<Long, BigDecimal>)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                dealerService, "resolvePendingOrderExposureMap", company, List.<Long>of());

    assertThat(noCompany).isEmpty();
    assertThat(nullDealerIds).isEmpty();
    assertThat(noDealerIds).isEmpty();
  }

  @Test
  void resolvePendingOrderExposureMap_skipsNullRowsAndDefaultsNullExposure() {
    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealerIds(
            eq(company), eq(List.of(1L, 2L)), any()))
        .thenReturn(
            Arrays.asList(
                null,
                new DealerCreditExposureView(null, new BigDecimal("50.00")),
                new DealerCreditExposureView(1L, null),
                new DealerCreditExposureView(2L, new BigDecimal("12.50"))));

    @SuppressWarnings("unchecked")
    Map<Long, BigDecimal> exposures =
        (Map<Long, BigDecimal>)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                dealerService, "resolvePendingOrderExposureMap", company, List.of(1L, 2L));

    assertThat(exposures)
        .containsEntry(1L, BigDecimal.ZERO)
        .containsEntry(2L, new BigDecimal("12.50"));
  }

  @Test
  void search_normalizesFiltersAndDefaultsMissingExposureRows() {
    Dealer dealer = dealer("D-MATCH", new BigDecimal("1000"), "NORTH");
    when(dealerRepository.searchFiltered(
            eq(company), eq("dealer"), eq("ACTIVE"), eq("NORTH"), any()))
        .thenReturn(List.of(dealer));
    when(dealerLedgerService.currentBalances(List.of(99L)))
        .thenReturn(Map.of(99L, new BigDecimal("50")));
    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealerIds(
            eq(company), eq(List.of(99L)), any()))
        .thenReturn(List.of());

    var results = dealerService.search("  dealer  ", " active ", " north ", " within_limit ");

    assertThat(results)
        .singleElement()
        .satisfies(result -> assertThat(result.creditStatus()).isEqualTo("WITHIN_LIMIT"));
  }

  @Test
  void search_rejectsUnknownCreditStatus() {
    assertThatThrownBy(() -> dealerService.search("", null, null, "not-real"))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
              assertThat(ex.getMessage())
                  .isEqualTo("creditStatus must be one of WITHIN_LIMIT, NEAR_LIMIT, OVER_LIMIT");
            });
  }

  @Test
  void updateDealer_usesPortalEmailAndResolvedPendingExposureInResponse() {
    Dealer dealer = dealer("D-UPDATE", new BigDecimal("1000"), "WEST");
    UserAccount portalUser = new UserAccount("portal@example.com", "TEST", "hash", "Portal");
    dealer.setPortalUser(portalUser);
    Account receivableAccount = new Account();
    ReflectionTestUtils.setField(receivableAccount, "id", 123L);
    receivableAccount.setCode("AR-D-UPDATE");
    dealer.setReceivableAccount(receivableAccount);
    when(dealerRepository.findByCompanyAndId(company, 99L)).thenReturn(Optional.of(dealer));
    when(dealerLedgerService.currentBalance(99L)).thenReturn(new BigDecimal("500"));
    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(
            eq(company), eq(dealer), any(), eq(null)))
        .thenReturn(new BigDecimal("400"));

    var response =
        dealerService.updateDealer(
            99L,
            new CreateDealerRequest(
                "  Updated Dealer  ",
                "  Updated Co  ",
                "updated@example.com",
                "8888888888",
                null,
                null,
                "29ABCDE1234F1Z5",
                "ka",
                null,
                null,
                " south "));

    assertThat(response.portalEmail()).isEqualTo("portal@example.com");
    assertThat(response.creditStatus()).isEqualTo("NEAR_LIMIT");
    assertThat(response.region()).isEqualTo("SOUTH");
  }

  @Test
  void
      resolvePendingOrderExposure_returnsZeroWhenDealerContextIsIncompleteOrRepositoryReturnsNull() {
    BigDecimal missingDealer =
        (BigDecimal)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                dealerService, "resolvePendingOrderExposure", new Object[] {null});

    Dealer dealerWithoutContext = new Dealer();
    BigDecimal missingContext =
        (BigDecimal)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                dealerService, "resolvePendingOrderExposure", dealerWithoutContext);

    Dealer dealer = dealer("D-NULL-EXPOSURE", new BigDecimal("1000"), "WEST");
    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(
            eq(company), eq(dealer), any(), eq(null)))
        .thenReturn(null);
    BigDecimal nullRepositoryExposure =
        (BigDecimal)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                dealerService, "resolvePendingOrderExposure", dealer);

    assertThat(missingDealer).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(missingContext).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(nullRepositoryExposure).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void creditUtilization_clampsAvailableCreditWhenCreditLimitIsMissing() {
    Dealer dealer = dealer("D-NO-LIMIT", null, "WEST");
    when(dealerRepository.findByCompanyAndId(company, 99L)).thenReturn(Optional.of(dealer));
    when(dealerLedgerService.currentBalance(99L)).thenReturn(new BigDecimal("75"));
    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(
            eq(company), eq(dealer), any(), eq(null)))
        .thenReturn(new BigDecimal("25"));

    var payload = dealerService.creditUtilization(99L);

    assertThat(payload)
        .containsEntry("dealerId", 99L)
        .containsEntry("dealerName", "D-NO-LIMIT Name")
        .doesNotContainKeys(
            "creditLimit",
            "outstandingAmount",
            "pendingOrderExposure",
            "creditUsed",
            "availableCredit");
    assertThat(payload.get("creditStatus")).isEqualTo("OVER_LIMIT");
  }

  @Test
  void creditUtilizationClampsNegativeLedgerBalanceWhenDealerHasCredit() {
    Dealer dealer = dealer("D-CREDIT", new BigDecimal("1000"), "WEST");
    ReflectionTestUtils.setField(dealer, "id", 109L);
    when(dealerRepository.findByCompanyAndId(company, 109L)).thenReturn(Optional.of(dealer));
    when(dealerLedgerService.currentBalance(109L)).thenReturn(new BigDecimal("-125"));
    when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(
            eq(company), eq(dealer), any(), eq(null)))
        .thenReturn(new BigDecimal("50"));

    var payload = dealerService.creditUtilization(109L);

    assertThat(payload)
        .containsEntry("dealerId", 109L)
        .containsEntry("dealerName", "D-CREDIT Name")
        .doesNotContainKeys(
            "creditLimit",
            "outstandingAmount",
            "pendingOrderExposure",
            "creditUsed",
            "availableCredit");
    assertThat(payload.get("creditStatus")).isEqualTo("WITHIN_LIMIT");
  }

  @Test
  void agingSummary_returnsDealerPayloadWhenDealerExists() {
    Dealer dealer = dealer("D-AGING", new BigDecimal("1000"), "WEST");
    when(dealerRepository.findByCompanyAndId(company, 77L)).thenReturn(Optional.of(dealer));
    when(companyClock.today(company)).thenReturn(java.time.LocalDate.parse("2026-02-23"));
    when(statementService.dealerAging(
            dealer, java.time.LocalDate.parse("2026-02-23"), "0-0,1-30,31-60,61-90,91"))
        .thenReturn(
            new AgingSummaryResponse(
                77L,
                "D-AGING Name",
                new BigDecimal("275"),
                List.of(new AgingBucketDto("1-30 days", 1, 30, new BigDecimal("275")))));
    when(statementService.dealerOverdueInvoices(dealer, java.time.LocalDate.parse("2026-02-23")))
        .thenReturn(
            List.of(
                new OverdueInvoiceDto(
                    "INV-900",
                    java.time.LocalDate.parse("2026-01-11"),
                    java.time.LocalDate.parse("2026-02-10"),
                    13L,
                    new BigDecimal("275"))));

    var payload = dealerService.agingSummary(77L);

    assertThat(payload)
        .containsEntry("dealerId", 99L)
        .containsEntry("dealerName", "D-AGING Name")
        .containsEntry("totalOutstanding", new BigDecimal("275"));
    assertThat((List<java.util.Map<String, Object>>) payload.get("overdueInvoices"))
        .singleElement()
        .satisfies(
            entry ->
                assertThat((java.util.Map<String, Object>) entry)
                    .containsEntry("invoiceNumber", "INV-900")
                    .containsEntry("issueDate", java.time.LocalDate.parse("2026-01-11"))
                    .containsEntry("daysOverdue", 13L));
  }

  @Test
  void agingSummary_defaultsBucketsWhenLedgerBucketsAreMissing() {
    Dealer dealer = dealer("D-AGING-EMPTY", new BigDecimal("1000"), "WEST");
    when(dealerRepository.findByCompanyAndId(company, 98L)).thenReturn(Optional.of(dealer));
    when(companyClock.today(company)).thenReturn(java.time.LocalDate.parse("2026-02-23"));
    when(statementService.dealerAging(
            dealer, java.time.LocalDate.parse("2026-02-23"), "0-0,1-30,31-60,61-90,91"))
        .thenReturn(new AgingSummaryResponse(98L, "D-AGING-EMPTY Name", null, null));
    when(statementService.dealerOverdueInvoices(dealer, java.time.LocalDate.parse("2026-02-23")))
        .thenReturn(List.of());

    var payload = dealerService.agingSummary(98L);

    assertThat(payload.get("totalOutstanding")).isEqualTo(BigDecimal.ZERO);
    assertThat((java.util.Map<String, Object>) payload.get("agingBuckets"))
        .containsEntry("current", BigDecimal.ZERO)
        .containsEntry("1-30 days", BigDecimal.ZERO)
        .containsEntry("31-60 days", BigDecimal.ZERO)
        .containsEntry("61-90 days", BigDecimal.ZERO)
        .containsEntry("90+ days", BigDecimal.ZERO);
    assertThat(payload.get("overdueInvoices")).isEqualTo(List.of());
  }

  @Test
  void agingSummary_mapsLedgerBucketsAndSkipsUnknownOrNullEntries() {
    Dealer dealer = dealer("D-AGING-MAP", new BigDecimal("1000"), "WEST");
    when(dealerRepository.findByCompanyAndId(company, 97L)).thenReturn(Optional.of(dealer));
    when(companyClock.today(company)).thenReturn(java.time.LocalDate.parse("2026-02-23"));
    when(statementService.dealerAging(
            dealer, java.time.LocalDate.parse("2026-02-23"), "0-0,1-30,31-60,61-90,91"))
        .thenReturn(
            new AgingSummaryResponse(
                97L,
                "D-AGING-MAP Name",
                new BigDecimal("625"),
                Arrays.asList(
                    new AgingBucketDto("0-0 days", 0, 0, new BigDecimal("25")),
                    new AgingBucketDto("1-30 days", 1, 30, new BigDecimal("100")),
                    new AgingBucketDto("31-60 days", 31, 60, new BigDecimal("150")),
                    new AgingBucketDto("61-90 days", 61, 90, new BigDecimal("175")),
                    new AgingBucketDto("91+ days", 91, null, new BigDecimal("175")),
                    new AgingBucketDto("Credit Balance", 0, 0, new BigDecimal("-15")),
                    null,
                    new AgingBucketDto("1-30 days", 1, 30, null),
                    new AgingBucketDto("unknown", 10, 20, new BigDecimal("999")))));
    when(statementService.dealerOverdueInvoices(dealer, java.time.LocalDate.parse("2026-02-23")))
        .thenReturn(
            Arrays.asList(
                null,
                new OverdueInvoiceDto(
                    "INV-901",
                    java.time.LocalDate.parse("2026-01-10"),
                    java.time.LocalDate.parse("2026-02-01"),
                    22L,
                    new BigDecimal("175"))));

    var payload = dealerService.agingSummary(97L);

    assertThat(payload).containsEntry("totalOutstanding", new BigDecimal("625"));
    assertThat((java.util.Map<String, Object>) payload.get("agingBuckets"))
        .containsEntry("current", new BigDecimal("25"))
        .containsEntry("1-30 days", new BigDecimal("100"))
        .containsEntry("31-60 days", new BigDecimal("150"))
        .containsEntry("61-90 days", new BigDecimal("175"))
        .containsEntry("90+ days", new BigDecimal("175"));
    assertThat((List<java.util.Map<String, Object>>) payload.get("overdueInvoices"))
        .singleElement()
        .satisfies(
            entry ->
                assertThat((java.util.Map<String, Object>) entry)
                    .containsEntry("invoiceNumber", "INV-901")
                    .containsEntry("issueDate", java.time.LocalDate.parse("2026-01-10"))
                    .containsEntry("daysOverdue", 22L));
  }

  @Test
  void overdueInvoicePayloadReturnsEmptyListForNullInput() {
    @SuppressWarnings("unchecked")
    List<java.util.Map<String, Object>> payload =
        (List<java.util.Map<String, Object>>)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                dealerService, "toOverdueInvoicePayload", new Object[] {null});

    assertThat(payload).isEqualTo(List.of());
  }

  @Test
  void portalAgingBucketsReturnDefaultsWhenResponseIsNull() {
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> buckets =
        (java.util.Map<String, Object>)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                dealerService, "toPortalAgingBuckets", new Object[] {null});

    assertThat(buckets)
        .containsEntry("current", BigDecimal.ZERO)
        .containsEntry("90+ days", BigDecimal.ZERO);
  }

  @Test
  void portalAgingBucketsIgnoreSyntheticCreditBalanceBucket() {
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> buckets =
        (java.util.Map<String, Object>)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                dealerService,
                "toPortalAgingBuckets",
                new AgingSummaryResponse(
                    99L,
                    "Dealer Name",
                    BigDecimal.ZERO,
                    List.of(new AgingBucketDto("Credit Balance", 0, 0, new BigDecimal("-25")))));

    assertThat(buckets).containsEntry("current", BigDecimal.ZERO);
  }

  @Test
  void safeReturnsProvidedValueWhenPresent() {
    BigDecimal value =
        (BigDecimal)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                dealerService, "safe", new BigDecimal("25.00"));

    assertThat(value).isEqualByComparingTo("25.00");
  }

  @Test
  void ledgerView_returnsDealerPayloadWhenDealerExists() {
    Dealer dealer = dealer("D-LEDGER", new BigDecimal("1000"), "WEST");
    when(dealerRepository.findByCompanyAndId(company, 78L)).thenReturn(Optional.of(dealer));
    when(dealerLedgerService.entries(dealer)).thenReturn(List.of());

    var payload = dealerService.ledgerView(78L);

    assertThat(payload).containsEntry("dealerId", 99L).containsEntry("dealerName", "D-LEDGER Name");
    assertThat(payload.get("entries")).isEqualTo(List.of());
  }

  @Test
  void creditUtilization_returnsNotFoundWhenDealerDoesNotExist() {
    when(dealerRepository.findByCompanyAndId(company, 404L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> dealerService.creditUtilization(404L))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
              assertThat(ex.getMessage()).isEqualTo("Dealer not found");
            });
  }

  @Test
  void agingSummary_returnsNotFoundWhenDealerDoesNotExist() {
    when(dealerRepository.findByCompanyAndId(company, 405L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> dealerService.agingSummary(405L))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
              assertThat(ex.getMessage()).isEqualTo("Dealer not found");
            });
  }

  @Test
  void ledgerView_returnsNotFoundWhenDealerDoesNotExist() {
    when(dealerRepository.findByCompanyAndId(company, 406L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> dealerService.ledgerView(406L))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
              assertThat(ex.getMessage()).isEqualTo("Dealer not found");
            });
  }

  @Test
  void createDealer_reusesExistingDealerByContactEmail() {
    Dealer existing = new Dealer();
    existing.setCompany(company);
    ReflectionTestUtils.setField(existing, "id", 77L);
    existing.setCode("LEGACY-DEALER");
    existing.setName("Legacy Name");
    existing.setStatus("INACTIVE");

    when(dealerRepository.findByCompanyAndEmailIgnoreCase(eq(company), eq("dealer@example.com")))
        .thenReturn(Optional.of(existing));

    dealerService.createDealer(request());

    ArgumentCaptor<Dealer> dealerCaptor = ArgumentCaptor.forClass(Dealer.class);
    verify(dealerRepository, atLeastOnce()).save(dealerCaptor.capture());
    Dealer saved = dealerCaptor.getAllValues().get(dealerCaptor.getAllValues().size() - 1);
    assertThat(saved.getId()).isEqualTo(77L);
    assertThat(saved.getCode()).isEqualTo("LEGACY-DEALER");
    assertThat(saved.getStatus()).isEqualTo("ACTIVE");
  }

  @Test
  void createDealer_preservesBlockedStatusWhenReusingExistingDealerByContactEmail() {
    Dealer existing = new Dealer();
    existing.setCompany(company);
    ReflectionTestUtils.setField(existing, "id", 78L);
    existing.setCode("BLOCKED-DEALER");
    existing.setName("Blocked Dealer");
    existing.setStatus("BLOCKED");

    when(dealerRepository.findByCompanyAndEmailIgnoreCase(eq(company), eq("dealer@example.com")))
        .thenReturn(Optional.of(existing));

    dealerService.createDealer(request());

    ArgumentCaptor<Dealer> dealerCaptor = ArgumentCaptor.forClass(Dealer.class);
    verify(dealerRepository, atLeastOnce()).save(dealerCaptor.capture());
    Dealer saved = dealerCaptor.getAllValues().get(dealerCaptor.getAllValues().size() - 1);
    assertThat(saved.getId()).isEqualTo(78L);
    assertThat(saved.getCode()).isEqualTo("BLOCKED-DEALER");
    assertThat(saved.getStatus()).isEqualTo("BLOCKED");
  }

  private CreateDealerRequest request() {
    return new CreateDealerRequest(
        "Test Dealer",
        "Test Dealer Co",
        "dealer@example.com",
        "9999999999",
        "Address",
        new BigDecimal("1000.00"));
  }

  private Dealer dealer(String code, BigDecimal creditLimit, String region) {
    Dealer dealer = new Dealer();
    Long id =
        switch (code) {
          case "D-WITHIN", "D-CREDIT" -> 1L;
          case "D-NEAR" -> 2L;
          case "D-ACTIVE" -> 3L;
          case "D-HOLD" -> 4L;
          default -> 99L;
        };
    ReflectionTestUtils.setField(dealer, "id", id);
    dealer.setCompany(company);
    dealer.setCode(code);
    dealer.setName(code + " Name");
    dealer.setCreditLimit(creditLimit);
    dealer.setRegion(region);
    dealer.setStateCode("KA");
    return dealer;
  }
}
