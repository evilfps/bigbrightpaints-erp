package com.bigbrightpaints.erp.modules.demo.controller;

import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
public class DemoController {

    @GetMapping("/ping")
    public ResponseEntity<ApiResponse<String>> ping() {
        String response = ValidationUtils.requireNotBlank("pong", "pingResponse");
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
