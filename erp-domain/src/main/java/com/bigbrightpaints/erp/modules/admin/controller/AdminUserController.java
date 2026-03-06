package com.bigbrightpaints.erp.modules.admin.controller;

import com.bigbrightpaints.erp.modules.admin.dto.CreateUserRequest;
import com.bigbrightpaints.erp.modules.admin.dto.UpdateUserRequest;
import com.bigbrightpaints.erp.modules.admin.dto.UserDto;
import com.bigbrightpaints.erp.modules.admin.service.AdminUserService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasAuthority('ROLE_ADMIN') or hasAuthority('ROLE_SUPER_ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<UserDto>>> list() {
        return ResponseEntity.ok(ApiResponse.success(adminUserService.listUsers()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<UserDto>> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.ok(ApiResponse.success("User created", adminUserService.createUser(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<UserDto>> update(@PathVariable Long id,
                                                        @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(ApiResponse.success("User updated", adminUserService.updateUser(id, request)));
    }

    @PostMapping("/{userId}/force-reset-password")
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<String>> forceResetPassword(@PathVariable Long userId) {
        adminUserService.forceResetPassword(userId);
        return ResponseEntity.ok(ApiResponse.success("Password reset link sent", "OK"));
    }

    @PutMapping("/{userId}/status")
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<UserDto>> updateStatus(@PathVariable Long userId,
                                                             @Valid @RequestBody UpdateUserStatusRequest request) {
        return ResponseEntity.ok(ApiResponse.success("User status updated", adminUserService.updateUserStatus(userId, request.enabled().booleanValue())));
    }

    @PatchMapping("/{id}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> suspend(@PathVariable Long id) {
        adminUserService.suspend(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/unsuspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> unsuspend(@PathVariable Long id) {
        adminUserService.unsuspend(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/mfa/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> disableMfa(@PathVariable Long id) {
        adminUserService.disableMfa(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        adminUserService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}
