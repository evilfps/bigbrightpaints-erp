package com.bigbrightpaints.erp.modules.auth.controller;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.auth.service.AuthService;
import com.bigbrightpaints.erp.modules.auth.service.PasswordResetService;
import com.bigbrightpaints.erp.modules.auth.service.PasswordService;
import com.bigbrightpaints.erp.modules.auth.web.AuthResponse;
import com.bigbrightpaints.erp.modules.auth.web.ForgotPasswordRequest;
import com.bigbrightpaints.erp.modules.auth.web.LoginRequest;
import com.bigbrightpaints.erp.modules.auth.web.MeResponse;
import com.bigbrightpaints.erp.modules.auth.web.RefreshTokenRequest;
import com.bigbrightpaints.erp.modules.auth.web.ResetPasswordRequest;
import com.bigbrightpaints.erp.modules.auth.web.ChangePasswordRequest;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordService passwordService;
    private final PasswordResetService passwordResetService;

    public AuthController(AuthService authService,
                          PasswordService passwordService,
                          PasswordResetService passwordResetService) {
        this.authService = authService;
        this.passwordService = passwordService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> logout(@RequestParam(required = false) String refreshToken,
                                       Authentication authentication) {
        String accessToken = null;
        if (authentication != null) {
            Object credentials = authentication.getCredentials();
            if (credentials instanceof String token && !token.isBlank()) {
                accessToken = token;
            }
        }
        authService.logout(refreshToken, accessToken);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<MeResponse>> me(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(ApiResponse.failure("Unauthenticated"));
        }
        String companyCode = CompanyContextHolder.getCompanyCode();
        List<String> roles = principal.getUser().getRoles().stream()
                .map(role -> role.getName())
                .sorted()
                .toList();
        List<String> permissions = principal.getUser().getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(permission -> permission.getCode())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        MeResponse payload = new MeResponse(principal.getUsername(), principal.getUser().getDisplayName(),
                companyCode, principal.getUser().isMfaEnabled(), principal.getUser().isMustChangePassword(), roles, permissions);
        return ResponseEntity.ok(ApiResponse.success(payload));
    }

    @PostMapping("/password/change")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<String>> changePassword(@AuthenticationPrincipal UserPrincipal principal,
                                                              @Valid @RequestBody ChangePasswordRequest request) {
        if (principal == null) {
            return ResponseEntity.status(401).body(ApiResponse.failure("Unauthenticated"));
        }
        passwordService.changePassword(principal.getUser(), request);
        return ResponseEntity.ok(ApiResponse.success("Password changed successfully", "OK"));
    }

    @PostMapping("/password/forgot")
    public ResponseEntity<ApiResponse<String>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request.email());
        return ResponseEntity.ok(ApiResponse.success("If the email exists, a reset link has been sent", "OK"));
    }

    /**
     * @deprecated Use {@code /api/v1/auth/password/forgot}. This endpoint is retained for backward compatibility.
     */
    @Deprecated
    @ResponseStatus(HttpStatus.GONE)
    @PostMapping("/password/forgot/superadmin")
    public ApiResponse<Map<String, String>> forgotPasswordForSuperAdmin(
            @Valid @RequestBody ForgotPasswordRequest request) {
        return ApiResponse.failure(
                "Deprecated super-admin forgot-password alias has been retired; use the supported recovery routes",
                Map.of(
                        "canonicalPath", "/api/v1/auth/password/forgot",
                        "supportResetPath", "/api/v1/companies/{id}/support/admin-password-reset"));
    }

    @PostMapping("/password/reset")
    public ResponseEntity<ApiResponse<String>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.token(), request.newPassword(), request.confirmPassword());
        return ResponseEntity.ok(ApiResponse.success("Password has been reset successfully", "OK"));
    }
}
