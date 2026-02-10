package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.dto.CreateDealerRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DealerServiceTest {

    @Mock
    private DealerRepository dealerRepository;
    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private RoleService roleService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private EmailService emailService;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService dealerLedgerService;

    private DealerService dealerService;
    private Company company;

    @BeforeEach
    void setUp() {
        dealerService = new DealerService(
                dealerRepository,
                companyContextService,
                userAccountRepository,
                roleService,
                passwordEncoder,
                emailService,
                accountRepository,
                dealerLedgerService
        );

        company = new Company();
        ReflectionTestUtils.setField(company, "id", 1L);
        company.setCode("TEST");

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(dealerRepository.findByCompanyAndPortalUserEmail(eq(company), anyString())).thenReturn(Optional.empty());
        when(dealerRepository.findByCompanyAndCodeIgnoreCase(eq(company), anyString())).thenReturn(Optional.empty());
        when(dealerRepository.save(any(Dealer.class))).thenAnswer(invocation -> {
            Dealer dealer = invocation.getArgument(0);
            if (dealer.getId() == null) {
                ReflectionTestUtils.setField(dealer, "id", 99L);
            }
            return dealer;
        });
        when(userAccountRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(userAccountRepository.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");

        Role dealerRole = new Role();
        dealerRole.setName("ROLE_DEALER");
        when(roleService.ensureRoleExists("ROLE_DEALER")).thenReturn(dealerRole);

        when(accountRepository.findByCompanyAndCodeIgnoreCase(eq(company), anyString())).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            if (account.getId() == null) {
                ReflectionTestUtils.setField(account, "id", 123L);
            }
            return account;
        });
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void createDealer_sendsCredentialsOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        dealerService.createDealer(request());

        verify(emailService, never()).sendUserCredentialsEmail(anyString(), anyString(), anyString());
        List<TransactionSynchronization> synchronizations = new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        assertThat(synchronizations).hasSize(1);

        synchronizations.forEach(TransactionSynchronization::afterCommit);

        verify(emailService).sendUserCredentialsEmail(eq("dealer@example.com"), eq("Test Dealer"), anyString());
    }

    @Test
    void createDealer_doesNotSendCredentialsWhenTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();

        dealerService.createDealer(request());

        List<TransactionSynchronization> synchronizations = new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(emailService, never()).sendUserCredentialsEmail(anyString(), anyString(), anyString());
    }

    @Test
    void createDealer_linksReceivableAccountUnderArControlWhenPresent() {
        Account arControl = new Account();
        arControl.setType(AccountType.ASSET);
        arControl.setCode("AR");
        when(accountRepository.findByCompanyAndCodeIgnoreCase(eq(company), eq("AR"))).thenReturn(Optional.of(arControl));

        dealerService.createDealer(request());

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getParent()).isSameAs(arControl);
    }

    private CreateDealerRequest request() {
        return new CreateDealerRequest(
                "Test Dealer",
                "Test Dealer Co",
                "dealer@example.com",
                "9999999999",
                "Address",
                new BigDecimal("1000.00")
        );
    }
}
