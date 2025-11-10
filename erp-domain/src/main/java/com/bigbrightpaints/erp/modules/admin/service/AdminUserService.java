package com.bigbrightpaints.erp.modules.admin.service;

import com.bigbrightpaints.erp.modules.admin.dto.CreateUserRequest;
import com.bigbrightpaints.erp.modules.admin.dto.UpdateUserRequest;
import com.bigbrightpaints.erp.modules.admin.dto.UserDto;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AdminUserService {

    private final UserAccountRepository userRepository;
    private final CompanyRepository companyRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminUserService(UserAccountRepository userRepository,
                            CompanyRepository companyRepository,
                            RoleRepository roleRepository,
                            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<UserDto> listUsers() {
        return userRepository.findAll().stream().map(this::toDto).toList();
    }

    @Transactional
    public UserDto createUser(CreateUserRequest request) {
        UserAccount user = new UserAccount(request.email(), passwordEncoder.encode(request.password()), request.displayName());
        attachCompanies(user, request.companyIds());
        attachRoles(user, request.roles());
        return toDto(userRepository.save(user));
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
            Role role = roleRepository.findByName(roleName).orElseGet(() -> {
                Role newRole = new Role();
                newRole.setName(roleName);
                newRole.setDescription(roleName);
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
