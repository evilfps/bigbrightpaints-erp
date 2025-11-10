package com.bigbrightpaints.erp.modules.auth.controller;

import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.auth.service.MfaService;
import com.bigbrightpaints.erp.modules.auth.service.MfaService.MfaEnrollment;
import com.bigbrightpaints.erp.modules.auth.web.MfaActivateRequest;
import com.bigbrightpaints.erp.modules.auth.web.MfaDisableRequest;
import com.bigbrightpaints.erp.modules.auth.web.MfaSetupResponse;
import com.bigbrightpaints.erp.modules.auth.web.MfaStatusResponse;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/mfa")
public class MfaController {

    private final MfaService mfaService;

    public MfaController(MfaService mfaService) {
        this.mfaService = mfaService;
    }

    @PostMapping("/setup")
    public ResponseEntity<ApiResponse<MfaSetupResponse>> setup(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.failure("Unauthenticated"));
        }
        MfaEnrollment enrollment = mfaService.beginEnrollment(principal.getUser());
        MfaSetupResponse payload = new MfaSetupResponse(enrollment.secret(), enrollment.qrUri(), enrollment.recoveryCodes());
        return ResponseEntity.ok(ApiResponse.success("MFA enrollment started", payload));
    }

    @PostMapping("/activate")
    public ResponseEntity<ApiResponse<MfaStatusResponse>> activate(@AuthenticationPrincipal UserPrincipal principal,
                                                                   @Valid @RequestBody MfaActivateRequest request) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.failure("Unauthenticated"));
        }
        mfaService.activate(principal.getUser(), request.code());
        return ResponseEntity.ok(ApiResponse.success("MFA enabled", new MfaStatusResponse(true)));
    }

    @PostMapping("/disable")
    public ResponseEntity<ApiResponse<MfaStatusResponse>> disable(@AuthenticationPrincipal UserPrincipal principal,
                                                                  @Valid @RequestBody MfaDisableRequest request) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.failure("Unauthenticated"));
        }
        mfaService.disable(principal.getUser(), request.code(), request.recoveryCode());
        return ResponseEntity.ok(ApiResponse.success("MFA disabled", new MfaStatusResponse(false)));
    }
}

