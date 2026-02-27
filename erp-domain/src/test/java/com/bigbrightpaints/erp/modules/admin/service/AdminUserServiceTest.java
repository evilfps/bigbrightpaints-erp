package com.bigbrightpaints.erp.modules.admin.service;

import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.admin.dto.CreateUserRequest;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.RefreshTokenService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private UserAccountRepository userRepository;
    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private RoleService roleService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private EmailService emailService;
    @Mock
    private TokenBlacklistService tokenBlacklistService;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private DealerRepository dealerRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private TenantRuntimePolicyService tenantRuntimePolicyService;

    private AdminUserService service;
    private Company company;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(
                userRepository,
                companyContextService,
                companyRepository,
                roleRepository,
                roleService,
                passwordEncoder,
                emailService,
                tokenBlacklistService,
                refreshTokenService,
                dealerRepository,
                accountRepository,
                tenantRuntimePolicyService
        );
        company = new Company();
        ReflectionTestUtils.setField(company, "id", 1L);
        company.setCode("TEST");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        lenient().when(passwordEncoder.encode(any())).thenReturn("encoded");
        lenient().when(userRepository.save(any(UserAccount.class))).thenAnswer(invocation -> {
            UserAccount user = invocation.getArgument(0);
            if (user.getId() == null) {
                ReflectionTestUtils.setField(user, "id", 200L);
            }
            return user;
        });
        lenient().when(roleService.ensureRoleExists(anyString())).thenAnswer(invocation -> {
            Role role = new Role();
            role.setName(invocation.getArgument(0));
            return role;
        });
        lenient().when(dealerRepository.save(any(Dealer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createUser_relinksExistingDealerByEmailAndReactivatesReceivableAccount() {
        Dealer existingDealer = new Dealer();
        existingDealer.setCompany(company);
        ReflectionTestUtils.setField(existingDealer, "id", 44L);
        existingDealer.setCode("LEGACY44");
        existingDealer.setName("Legacy Dealer");
        existingDealer.setStatus("INACTIVE");
        existingDealer.setEmail("dealer@example.com");

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-LEGACY44");
        receivable.setActive(false);
        existingDealer.setReceivableAccount(receivable);

        when(dealerRepository.findByCompanyAndPortalUserEmail(company, "dealer@example.com"))
                .thenReturn(Optional.empty());
        when(dealerRepository.findByCompanyAndEmailIgnoreCase(company, "dealer@example.com"))
                .thenReturn(Optional.of(existingDealer));

        service.createUser(new CreateUserRequest(
                "dealer@example.com",
                "Password@123",
                "Dealer User",
                List.of(1L),
                List.of("ROLE_DEALER")
        ));

        ArgumentCaptor<Dealer> dealerCaptor = ArgumentCaptor.forClass(Dealer.class);
        verify(dealerRepository).save(dealerCaptor.capture());
        Dealer savedDealer = dealerCaptor.getValue();
        assertThat(savedDealer.getId()).isEqualTo(44L);
        assertThat(savedDealer.getStatus()).isEqualTo("ACTIVE");
        assertThat(savedDealer.getPortalUser()).isNotNull();
        assertThat(savedDealer.getPortalUser().getEmail()).isEqualTo("dealer@example.com");
        assertThat(receivable.isActive()).isTrue();
        verify(accountRepository).save(receivable);
    }

    @Test
    void createUser_superAdminCanAssignUserToRequestedTenantCompany() {
        Company foreignCompany = new Company();
        ReflectionTestUtils.setField(foreignCompany, "id", 2L);
        foreignCompany.setCode("FOREIGN");

        when(companyRepository.findAllById(any())).thenReturn(List.of(foreignCompany));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "super-admin@bbp.com",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));

        try {
            service.createUser(new CreateUserRequest(
                    "ops-user@example.com",
                    "Password@123",
                    "Ops User",
                    List.of(2L),
                    List.of("ROLE_SALES")
            ));
        } finally {
            SecurityContextHolder.clearContext();
        }

        ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userRepository).save(userCaptor.capture());
        UserAccount savedUser = userCaptor.getValue();
        assertThat(savedUser.getCompanies())
                .extracting(Company::getCode)
                .containsExactly("FOREIGN");
        verify(tenantRuntimePolicyService).assertCanAddEnabledUser(foreignCompany, "ADMIN_USER_CREATE");
    }

    @Test
    void createUser_nonSuperAdminRejectsForeignCompanyScope() {
        assertThatThrownBy(() -> service.createUser(new CreateUserRequest(
                "scope-user@example.com",
                "Password@123",
                "Scope User",
                List.of(2L),
                List.of("ROLE_SALES")
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User must be assigned to the active company");
    }

    @Test
    void createUser_superAdminRejectsMissingCompanyAssignments() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "super-admin@bbp.com",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        try {
            assertThatThrownBy(() -> service.createUser(new CreateUserRequest(
                    "missing-company@example.com",
                    "Password@123",
                    "Missing Company",
                    List.of(),
                    List.of("ROLE_SALES")
            ))).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("User must belong to an active company");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void createUser_superAdminRejectsUnknownCompanyId() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "super-admin@bbp.com",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        when(companyRepository.findAllById(any())).thenReturn(List.of());
        try {
            assertThatThrownBy(() -> service.createUser(new CreateUserRequest(
                    "unknown-company@example.com",
                    "Password@123",
                    "Unknown Company",
                    List.of(99L),
                    List.of("ROLE_SALES")
            ))).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Company not found: 99");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void createUser_nonSuperAdminCannotAssignSuperAdminRole() {
        assertThatThrownBy(() -> service.createUser(new CreateUserRequest(
                "tenant-user@example.com",
                "Password@123",
                "Tenant User",
                List.of(1L),
                List.of("ROLE_SUPER_ADMIN")
        ))).isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SUPER_ADMIN authority required");
    }

    @Test
    void createUser_superAdminCanAssignSuperAdminRole() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "super-admin@bbp.com",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        when(companyRepository.findAllById(any())).thenReturn(List.of(company));
        try {
            service.createUser(new CreateUserRequest(
                    "platform-owner@example.com",
                    "Password@123",
                    "Platform Owner",
                    List.of(1L),
                    List.of("ROLE_SUPER_ADMIN")
            ));
            verify(userRepository).save(any(UserAccount.class));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
