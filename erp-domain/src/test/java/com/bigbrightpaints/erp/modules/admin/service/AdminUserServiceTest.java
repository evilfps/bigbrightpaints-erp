package com.bigbrightpaints.erp.modules.admin.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.admin.dto.CreateUserRequest;
import com.bigbrightpaints.erp.modules.admin.dto.UpdateUserRequest;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.PasswordResetService;
import com.bigbrightpaints.erp.modules.auth.service.RefreshTokenService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
    private PasswordResetService passwordResetService;
    @Mock
    private AuditService auditService;
    @Mock
    private AuditLogRepository auditLogRepository;
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
                roleService,
                passwordEncoder,
                emailService,
                tokenBlacklistService,
                refreshTokenService,
                passwordResetService,
                auditService,
                auditLogRepository,
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
        ))).isInstanceOf(ApplicationException.class)
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
            ))).isInstanceOf(ApplicationException.class)
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
            ))).isInstanceOf(ApplicationException.class)
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

    @Test
    void listUsers_includesLastLoginAtDerivedFromLatestLoginAuditEvent() {
        UserAccount user = new UserAccount("audited-user@example.com", "hash", "Audited User");
        ReflectionTestUtils.setField(user, "id", 301L);
        user.addCompany(company);
        Role role = new Role();
        role.setName("ROLE_ADMIN");
        user.addRole(role);

        AuditLog latestLogin = new AuditLog();
        latestLogin.setEventType(AuditEvent.LOGIN_SUCCESS);
        latestLogin.setUsername("AUDITED-USER@example.com");
        latestLogin.setTimestamp(LocalDateTime.of(2026, 1, 5, 10, 15, 30));

        when(userRepository.findDistinctByCompanies_Id(company.getId())).thenReturn(List.of(user));
        when(auditLogRepository.findLatestTimestampByEventTypeAndUsernameIn(
                AuditEvent.LOGIN_SUCCESS,
                java.util.Set.of("audited-user@example.com")))
                .thenReturn(List.of(new AuditLogRepository.UsernameLastLoginProjection() {
                    @Override
                    public String getUsernameKey() {
                        return "audited-user@example.com";
                    }

                    @Override
                    public LocalDateTime getLastLoginAt() {
                        return latestLogin.getTimestamp();
                    }
                }));

        var results = service.listUsers();

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().lastLoginAt())
                .isEqualTo(LocalDateTime.of(2026, 1, 5, 10, 15, 30).atZone(ZoneOffset.UTC).toInstant());
    }

    @Test
    void updateUserStatus_disablingUserRevokesTokensSendsNotificationAndAudits() {
        UserAccount user = new UserAccount("status-user@example.com", "hash", "Status User");
        ReflectionTestUtils.setField(user, "id", 302L);
        user.addCompany(company);
        Role role = new Role();
        role.setName("ROLE_ADMIN");
        user.addRole(role);

        when(userRepository.findById(302L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(auditLogRepository.findFirstByEventTypeAndUsernameIgnoreCaseOrderByTimestampDesc(
                AuditEvent.LOGIN_SUCCESS,
                "status-user@example.com"))
                .thenReturn(Optional.empty());

        var response = service.updateUserStatus(302L, false);

        assertThat(response.enabled()).isFalse();
        verify(tokenBlacklistService).revokeAllUserTokens("status-user@example.com");
        verify(refreshTokenService).revokeAllForUser("status-user@example.com");
        verify(emailService).sendUserSuspendedEmail("status-user@example.com", "Status User");
        verify(auditService).logAuthSuccess(
                eq(AuditEvent.USER_DEACTIVATED),
                eq("UNKNOWN_AUTH_ACTOR"),
                eq("TEST"),
                any(Map.class));
    }

    @Test
    void updateUserStatus_enablingUserChecksQuotaAndDoesNotSendSuspensionEmail() {
        UserAccount user = new UserAccount("reenable-user@example.com", "hash", "Reenabled User");
        ReflectionTestUtils.setField(user, "id", 303L);
        user.setEnabled(false);
        user.addCompany(company);
        Role role = new Role();
        role.setName("ROLE_ADMIN");
        user.addRole(role);

        when(userRepository.findById(303L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(auditLogRepository.findFirstByEventTypeAndUsernameIgnoreCaseOrderByTimestampDesc(
                AuditEvent.LOGIN_SUCCESS,
                "reenable-user@example.com"))
                .thenReturn(Optional.empty());

        var response = service.updateUserStatus(303L, true);

        assertThat(response.enabled()).isTrue();
        verify(tenantRuntimePolicyService).assertCanAddEnabledUser(company, "ADMIN_USER_STATUS");
        verify(emailService, never()).sendUserSuspendedEmail(anyString(), anyString());
        verify(tokenBlacklistService, never()).revokeAllUserTokens("reenable-user@example.com");
        verify(refreshTokenService, never()).revokeAllForUser("reenable-user@example.com");
        verify(auditService).logAuthSuccess(
                eq(AuditEvent.USER_ACTIVATED),
                eq("UNKNOWN_AUTH_ACTOR"),
                eq("TEST"),
                any(Map.class));
    }

    @Test
    void forceResetPassword_delegatesToPasswordResetServiceAndAudits() {
        UserAccount user = new UserAccount("force-reset@example.com", "hash", "Force Reset");
        ReflectionTestUtils.setField(user, "id", 304L);
        user.addCompany(company);
        Role role = new Role();
        role.setName("ROLE_ADMIN");
        user.addRole(role);

        when(userRepository.findById(304L)).thenReturn(Optional.of(user));

        service.forceResetPassword(304L);

        verify(passwordResetService).requestResetByAdmin(user);
        verify(auditService).logAuthSuccess(
                eq(AuditEvent.PASSWORD_RESET_REQUESTED),
                eq("UNKNOWN_AUTH_ACTOR"),
                eq("TEST"),
                any(Map.class));
    }

    @Test
    void forceResetPassword_crossTenantUser_forTenantAdmin_masksTargetAsMissingWithoutLocking() {
        Company foreignCompany = new Company();
        ReflectionTestUtils.setField(foreignCompany, "id", 21L);
        foreignCompany.setCode("FOREIGN");

        UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
        ReflectionTestUtils.setField(foreignUser, "id", 311L);
        foreignUser.addCompany(foreignCompany);

        when(userRepository.findById(311L)).thenReturn(Optional.of(foreignUser));

        assertThatThrownBy(() -> service.forceResetPassword(311L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("User not found");

        verify(userRepository).findById(311L);
        verify(userRepository, never()).lockById(311L);
        verify(userRepository, never()).lockByIdAndCompanyId(311L, 1L);
        verify(passwordResetService, never()).requestResetByAdmin(any(UserAccount.class));
        verify(auditService).logAuthFailure(
                eq(AuditEvent.ACCESS_DENIED),
                eq("UNKNOWN_AUTH_ACTOR"),
                eq("TEST"),
                any(Map.class));
    }

    @Test
    void updateUserStatus_crossTenantUser_forTenantAdmin_masksTargetAsMissingWithoutLocking() {
        Company foreignCompany = new Company();
        ReflectionTestUtils.setField(foreignCompany, "id", 21L);
        foreignCompany.setCode("FOREIGN");

        UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
        ReflectionTestUtils.setField(foreignUser, "id", 312L);
        foreignUser.addCompany(foreignCompany);

        when(userRepository.findById(312L)).thenReturn(Optional.of(foreignUser));

        assertThatThrownBy(() -> service.updateUserStatus(312L, false))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("User not found");

        verify(userRepository).findById(312L);
        verify(userRepository, never()).lockById(312L);
        verify(userRepository, never()).lockByIdAndCompanyId(312L, 1L);
        verify(userRepository, never()).save(any(UserAccount.class));
        verify(auditService).logAuthFailure(
                eq(AuditEvent.ACCESS_DENIED),
                eq("UNKNOWN_AUTH_ACTOR"),
                eq("TEST"),
                any(Map.class));
    }

    @Test
    void updateUser_allowsSuperAdminToTargetForeignTenantUser() {
        Company foreignCompany = new Company();
        ReflectionTestUtils.setField(foreignCompany, "id", 21L);
        foreignCompany.setCode("FOREIGN");

        UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
        ReflectionTestUtils.setField(foreignUser, "id", 305L);
        foreignUser.addCompany(foreignCompany);
        Role role = new Role();
        role.setName("ROLE_SALES");
        foreignUser.addRole(role);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "super-admin@bbp.com",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        when(userRepository.findById(305L)).thenReturn(Optional.of(foreignUser));
        when(userRepository.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(auditLogRepository.findFirstByEventTypeAndUsernameIgnoreCaseOrderByTimestampDesc(
                AuditEvent.LOGIN_SUCCESS,
                "foreign-user@example.com"))
                .thenReturn(Optional.empty());

        try {
            var response = service.updateUser(
                    305L,
                    new UpdateUserRequest("Foreign User Updated", null, null, true));
            assertThat(response.displayName()).isEqualTo("Foreign User Updated");
        } finally {
            SecurityContextHolder.clearContext();
        }

        verify(userRepository).findById(305L);
        verify(userRepository, never()).findByIdAndCompanies_Id(eq(305L), any());
    }

    @Test
    void suspend_crossTenantUser_forTenantAdmin_usesScopedLockAndMasksTargetAsMissing() {
        Company foreignCompany = new Company();
        ReflectionTestUtils.setField(foreignCompany, "id", 21L);
        foreignCompany.setCode("FOREIGN");

        UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
        ReflectionTestUtils.setField(foreignUser, "id", 306L);
        foreignUser.addCompany(foreignCompany);

        when(userRepository.lockByIdAndCompanyId(306L, 1L)).thenReturn(Optional.empty());
        when(userRepository.findById(306L)).thenReturn(Optional.of(foreignUser));

        assertThatThrownBy(() -> service.suspend(306L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("User not found");

        verify(userRepository).lockByIdAndCompanyId(306L, 1L);
        verify(userRepository, never()).lockById(306L);
        verify(auditService).logAuthFailure(
                eq(AuditEvent.ACCESS_DENIED),
                eq("UNKNOWN_AUTH_ACTOR"),
                eq("TEST"),
                any(Map.class));
        verify(userRepository, never()).save(any(UserAccount.class));
    }

    @Test
    void unsuspend_crossTenantUser_forTenantAdmin_usesScopedLockAndMasksTargetAsMissing() {
        Company foreignCompany = new Company();
        ReflectionTestUtils.setField(foreignCompany, "id", 21L);
        foreignCompany.setCode("FOREIGN");

        UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
        ReflectionTestUtils.setField(foreignUser, "id", 308L);
        foreignUser.addCompany(foreignCompany);

        when(userRepository.lockByIdAndCompanyId(308L, 1L)).thenReturn(Optional.empty());
        when(userRepository.findById(308L)).thenReturn(Optional.of(foreignUser));

        assertThatThrownBy(() -> service.unsuspend(308L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("User not found");

        verify(userRepository).lockByIdAndCompanyId(308L, 1L);
        verify(userRepository, never()).lockById(308L);
        verify(auditService).logAuthFailure(
                eq(AuditEvent.ACCESS_DENIED),
                eq("UNKNOWN_AUTH_ACTOR"),
                eq("TEST"),
                any(Map.class));
        verify(userRepository, never()).save(any(UserAccount.class));
    }

    @Test
    void deleteUser_crossTenantUser_forTenantAdmin_usesScopedLockAndMasksTargetAsMissing() {
        Company foreignCompany = new Company();
        ReflectionTestUtils.setField(foreignCompany, "id", 21L);
        foreignCompany.setCode("FOREIGN");

        UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
        ReflectionTestUtils.setField(foreignUser, "id", 309L);
        foreignUser.addCompany(foreignCompany);

        when(userRepository.lockByIdAndCompanyId(309L, 1L)).thenReturn(Optional.empty());
        when(userRepository.findById(309L)).thenReturn(Optional.of(foreignUser));

        assertThatThrownBy(() -> service.deleteUser(309L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("User not found");

        verify(userRepository).lockByIdAndCompanyId(309L, 1L);
        verify(userRepository, never()).lockById(309L);
        verify(auditService).logAuthFailure(
                eq(AuditEvent.ACCESS_DENIED),
                eq("UNKNOWN_AUTH_ACTOR"),
                eq("TEST"),
                any(Map.class));
        verify(userRepository, never()).delete(any(UserAccount.class));
    }

    @Test
    void disableMfa_crossTenantUser_forTenantAdmin_usesScopedLockAndMasksTargetAsMissing() {
        Company foreignCompany = new Company();
        ReflectionTestUtils.setField(foreignCompany, "id", 21L);
        foreignCompany.setCode("FOREIGN");

        UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
        ReflectionTestUtils.setField(foreignUser, "id", 310L);
        foreignUser.addCompany(foreignCompany);

        when(userRepository.lockByIdAndCompanyId(310L, 1L)).thenReturn(Optional.empty());
        when(userRepository.findById(310L)).thenReturn(Optional.of(foreignUser));

        assertThatThrownBy(() -> service.disableMfa(310L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("User not found");

        verify(userRepository).lockByIdAndCompanyId(310L, 1L);
        verify(userRepository, never()).lockById(310L);
        verify(auditService).logAuthFailure(
                eq(AuditEvent.ACCESS_DENIED),
                eq("UNKNOWN_AUTH_ACTOR"),
                eq("TEST"),
                any(Map.class));
        verify(userRepository, never()).save(any(UserAccount.class));
    }

    @Test
    void suspend_allowsSuperAdminToTargetForeignTenantUser() {
        Company foreignCompany = new Company();
        ReflectionTestUtils.setField(foreignCompany, "id", 21L);
        foreignCompany.setCode("FOREIGN");

        UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
        ReflectionTestUtils.setField(foreignUser, "id", 307L);
        foreignUser.addCompany(foreignCompany);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "super-admin@bbp.com",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        when(userRepository.lockById(307L)).thenReturn(Optional.of(foreignUser));
        when(userRepository.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        try {
            service.suspend(307L);
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(foreignUser.isEnabled()).isFalse();
        verify(tokenBlacklistService).revokeAllUserTokens("foreign-user@example.com");
        verify(refreshTokenService).revokeAllForUser("foreign-user@example.com");
    }
}
