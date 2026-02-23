package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoicePdfService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class DealerPortalServiceTest {

    @Mock
    private DealerRepository dealerRepository;
    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private DealerLedgerService dealerLedgerService;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private InvoicePdfService invoicePdfService;
    @Mock
    private DealerService dealerService;
    @Mock
    private SalesOrderRepository salesOrderRepository;
    @Mock
    private CompanyClock companyClock;

    private DealerPortalService dealerPortalService;
    private Company company;

    @BeforeEach
    void setUp() {
        dealerPortalService = new DealerPortalService(
                dealerRepository,
                companyContextService,
                dealerLedgerService,
                invoiceRepository,
                invoicePdfService,
                dealerService,
                salesOrderRepository,
                companyClock
        );
        company = new Company();
        ReflectionTestUtils.setField(company, "id", 9L);
        company.setCode("TENANT");
        lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getCurrentDealer_bindsByAuthenticatedUserId() {
        UserAccount user = userWithId(100L, "dealer@tenant.com");
        Dealer dealer = dealerWithId(21L);
        authenticate(user, "ROLE_DEALER");
        when(dealerRepository.findAllByCompanyAndPortalUserId(company, 100L)).thenReturn(List.of(dealer));

        Dealer resolved = dealerPortalService.getCurrentDealer();

        assertThat(resolved).isSameAs(dealer);
        verify(dealerRepository, never()).findAllByCompanyAndPortalUserEmailIgnoreCase(any(), anyString());
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
    void getCurrentDealer_fallsBackToEmailWhenPrincipalUserIdMissing() {
        UserAccount user = new UserAccount("dealer@tenant.com", "hash", "Dealer");
        Dealer dealer = dealerWithId(33L);
        authenticate(user, "ROLE_DEALER");
        when(dealerRepository.findAllByCompanyAndPortalUserEmailIgnoreCase(company, "dealer@tenant.com"))
                .thenReturn(List.of(dealer));

        Dealer resolved = dealerPortalService.getCurrentDealer();

        assertThat(resolved).isSameAs(dealer);
        verify(dealerRepository, never()).findAllByCompanyAndPortalUserId(any(), any());
    }

    @Test
    void getCurrentDealer_failsClosedWhenEmailFallbackHasNoDealerMapping() {
        UserAccount user = new UserAccount("dealer@tenant.com", "hash", "Dealer");
        authenticate(user, "ROLE_DEALER");
        when(dealerRepository.findAllByCompanyAndPortalUserEmailIgnoreCase(company, "dealer@tenant.com"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> dealerPortalService.getCurrentDealer())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("mapping missing");
    }

    @Test
    void getCurrentDealer_failsClosedWhenEmailFallbackMapsToMultipleDealers() {
        UserAccount user = new UserAccount("dealer@tenant.com", "hash", "Dealer");
        authenticate(user, "ROLE_DEALER");
        when(dealerRepository.findAllByCompanyAndPortalUserEmailIgnoreCase(company, "dealer@tenant.com"))
                .thenReturn(List.of(dealerWithId(33L), dealerWithId(34L)));

        assertThatThrownBy(() -> dealerPortalService.getCurrentDealer())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Ambiguous dealer mapping");
    }

    @Test
    void getCurrentDealer_failsClosedWhenUserIdMissingDealerMappingEvenIfEmailMatches() {
        UserAccount user = userWithId(100L, "dealer@tenant.com");
        authenticate(user, "ROLE_DEALER");
        when(dealerRepository.findAllByCompanyAndPortalUserId(company, 100L))
                .thenReturn(List.of());

        assertThatThrownBy(() -> dealerPortalService.getCurrentDealer())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("mapping missing")
                .hasMessageContaining("userId:100");
        verify(dealerRepository, never()).findAllByCompanyAndPortalUserEmailIgnoreCase(any(), anyString());
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
        verify(dealerRepository, never()).findAllByCompanyAndPortalUserEmailIgnoreCase(any(), anyString());
    }

    @Test
    void getCurrentDealer_deniesWhenNoAuthenticationPresent() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> dealerPortalService.getCurrentDealer())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("No authenticated user");
    }

    @Test
    void getCurrentDealer_deniesWhenPrincipalIdentityIsBlank() {
        var auth = new UsernamePasswordAuthenticationToken(
                "   ",
                "token",
                List.of(new SimpleGrantedAuthority("ROLE_DEALER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThatThrownBy(() -> dealerPortalService.getCurrentDealer())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("No authenticated user identity");
    }

    private void authenticate(UserAccount user, String... authorities) {
        UserPrincipal principal = new UserPrincipal(user);
        var grantedAuthorities = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
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
        ReflectionTestUtils.setField(dealer, "id", id);
        return dealer;
    }
}
