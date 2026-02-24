package com.bigbrightpaints.erp.modules.auth.service;

import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantAdminProvisioningServiceTest {

    @Mock
    private UserAccountRepository userAccountRepository;

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

    @Test
    void provisionInitialAdmin_savesUserAndEmailsCredentials() {
        TenantAdminProvisioningService service = new TenantAdminProvisioningService(
                userAccountRepository,
                roleService,
                passwordEncoder,
                emailService,
                tokenBlacklistService,
                refreshTokenService);
        Company company = company(10L, "SKE", "SKE");
        Role adminRole = new Role();
        adminRole.setName("ROLE_ADMIN");
        when(userAccountRepository.findByEmailIgnoreCase("new-admin@ske.com")).thenReturn(Optional.empty());
        when(roleService.ensureRoleExists("ROLE_ADMIN")).thenReturn(adminRole);
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        when(userAccountRepository.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String email = service.provisionInitialAdmin(company, "new-admin@ske.com", "New Admin");

        assertThat(email).isEqualTo("new-admin@ske.com");
        verify(userAccountRepository).save(any(UserAccount.class));
        verify(emailService).sendUserCredentialsEmail(
                eq("new-admin@ske.com"),
                eq("New Admin"),
                any(),
                eq("SKE"));
    }

    @Test
    void resetTenantAdminPassword_rejectsUserOutsideCompany() {
        TenantAdminProvisioningService service = new TenantAdminProvisioningService(
                userAccountRepository,
                roleService,
                passwordEncoder,
                emailService,
                tokenBlacklistService,
                refreshTokenService);
        Company target = company(55L, "SKE", "SKE");
        Company other = company(56L, "OTH", "Other");
        UserAccount user = new UserAccount("admin@ske.com", "hash", "Admin");
        user.addCompany(other);
        Role adminRole = new Role();
        adminRole.setName("ROLE_ADMIN");
        user.addRole(adminRole);
        when(userAccountRepository.findByEmailIgnoreCase("admin@ske.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.resetTenantAdminPassword(target, "admin@ske.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not assigned to company");
    }

    private Company company(Long id, String code, String name) {
        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", id);
        company.setCode(code);
        company.setName(name);
        company.setTimezone("UTC");
        return company;
    }
}
