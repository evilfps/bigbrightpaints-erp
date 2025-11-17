package com.bigbrightpaints.erp.modules.admin.service;

import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.modules.admin.dto.CreateUserRequest;
import com.bigbrightpaints.erp.modules.admin.dto.UpdateUserRequest;
import com.bigbrightpaints.erp.modules.admin.dto.UserDto;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import jakarta.transaction.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Service
public class AdminUserService {

    private final UserAccountRepository userRepository;
    private final CompanyRepository companyRepository;
    private final RoleRepository roleRepository;
    private final RoleService roleService;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    public AdminUserService(UserAccountRepository userRepository,
                            CompanyRepository companyRepository,
                            RoleRepository roleRepository,
                            RoleService roleService,
                            PasswordEncoder passwordEncoder,
                            EmailService emailService) {
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.roleRepository = roleRepository;
        this.roleService = roleService;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
    }

    public List<UserDto> listUsers() {
        return userRepository.findAll().stream().map(this::toDto).toList();
    }

    @Transactional
    public UserDto createUser(CreateUserRequest request) {
        UserAccount user = new UserAccount(request.email(), passwordEncoder.encode(request.password()), request.displayName());
        attachCompanies(user, request.companyIds());
        attachRoles(user, request.roles());
        UserAccount saved = userRepository.save(user);
        emailService.sendUserCredentialsEmail(saved.getEmail(), saved.getDisplayName(), request.password());
        return toDto(saved);
    }

    @Transactional
    public UserDto updateUser(Long id, UpdateUserRequest request) {
        UserAccount user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        user.setDisplayName(request.displayName());
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }
        if (request.companyIds() != null && !request.companyIds().isEmpty()) {
            user.getCompanies().clear();
            attachCompanies(user, request.companyIds());
        }
        if (request.roles() != null && !request.roles().isEmpty()) {
            user.getRoles().clear();
            attachRoles(user, request.roles());
        }
        return toDto(user);
    }

    public void suspend(Long id) {
        userRepository.findById(id).ifPresent(user -> {
            user.setEnabled(false);
            userRepository.save(user);
        });
    }

    public void unsuspend(Long id) {
        userRepository.findById(id).ifPresent(user -> {
            user.setEnabled(true);
            userRepository.save(user);
        });
    }

    private void attachCompanies(UserAccount user, List<Long> companyIds) {
        List<Company> companies = companyRepository.findAllById(companyIds);
        if (companies.size() != companyIds.size()) {
            throw new IllegalArgumentException("One or more companies not found");
        }
        companies.forEach(user::addCompany);
    }

    private void attachRoles(UserAccount user, List<String> roles) {
        roles.forEach(roleName -> {
            if (!StringUtils.hasText(roleName)) {
                return;
            }
            String trimmed = roleName.trim();
            String normalized = trimmed.toUpperCase(Locale.ROOT);
            if (normalized.startsWith("ROLE_")) {
                if (!roleService.isSystemRole(trimmed)) {
                    throw new IllegalArgumentException("Unsupported platform role: " + trimmed);
                }
                user.addRole(roleService.ensureRoleExists(trimmed));
                return;
            }
            Role role = roleRepository.findByName(trimmed).orElseGet(() -> {
                Role newRole = new Role();
                newRole.setName(trimmed);
                newRole.setDescription(trimmed);
                return roleRepository.save(newRole);
            });
            user.addRole(role);
        });
    }

    private UserDto toDto(UserAccount user) {
        List<String> companies = user.getCompanies().stream().map(Company::getCode).toList();
        List<String> roles = user.getRoles().stream().map(Role::getName).toList();
        return new UserDto(user.getId(), user.getPublicId(), user.getEmail(), user.getDisplayName(),
                user.isEnabled(), user.isMfaEnabled(), roles, companies);
    }
}
