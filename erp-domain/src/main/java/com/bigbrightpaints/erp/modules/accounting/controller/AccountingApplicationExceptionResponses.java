package com.bigbrightpaints.erp.modules.accounting.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

final class AccountingApplicationExceptionResponses {

  private AccountingApplicationExceptionResponses() {
  }

  static ResponseEntity<ApiResponse<Map<String, Object>>> badRequest(
      ApplicationException ex, HttpServletRequest request) {
    return withStatus(ex, request, HttpStatus.BAD_REQUEST);
  }

  static ResponseEntity<ApiResponse<Map<String, Object>>> mappedStatus(
      ApplicationException ex, HttpServletRequest request) {
    return withStatus(ex, request, determineHttpStatus(ex.getErrorCode()));
  }

  private static ResponseEntity<ApiResponse<Map<String, Object>>> withStatus(
      ApplicationException ex, HttpServletRequest request, HttpStatus status) {
    return ResponseEntity.status(status)
        .body(ApiResponse.failure(ex.getUserMessage(), buildErrorData(ex, request)));
  }

  private static Map<String, Object> buildErrorData(
      ApplicationException ex, HttpServletRequest request) {
    Map<String, Object> errorData = new HashMap<>();
    errorData.put("code", ex.getErrorCode().getCode());
    errorData.put("message", ex.getUserMessage());
    errorData.put("reason", ex.getUserMessage());
    errorData.put("path", request != null ? request.getRequestURI() : null);
    errorData.put("traceId", UUID.randomUUID().toString());
    Map<String, Object> details = ex.getDetails();
    if (!details.isEmpty()) {
      errorData.put("details", details);
    }
    return errorData;
  }

  private static HttpStatus determineHttpStatus(ErrorCode errorCode) {
    if (errorCode == null) {
      return HttpStatus.INTERNAL_SERVER_ERROR;
    }
    if (errorCode == ErrorCode.BUSINESS_ENTITY_NOT_FOUND || errorCode == ErrorCode.FILE_NOT_FOUND) {
      return HttpStatus.NOT_FOUND;
    }
    if (errorCode == ErrorCode.AUTH_MFA_REQUIRED) {
      return HttpStatus.PRECONDITION_REQUIRED;
    }
    if (errorCode == ErrorCode.MODULE_DISABLED) {
      return HttpStatus.FORBIDDEN;
    }
    if (errorCode == ErrorCode.SYSTEM_RATE_LIMIT_EXCEEDED) {
      return HttpStatus.TOO_MANY_REQUESTS;
    }
    String prefix = errorCode.getCode().split("_")[0];
    return switch (prefix) {
      case "AUTH" -> HttpStatus.UNAUTHORIZED;
      case "VAL" -> HttpStatus.BAD_REQUEST;
      case "BUS", "CONC", "DATA" -> HttpStatus.CONFLICT;
      case "SYS" -> HttpStatus.SERVICE_UNAVAILABLE;
      case "INT" -> HttpStatus.BAD_GATEWAY;
      case "FILE" -> HttpStatus.UNPROCESSABLE_ENTITY;
      default -> HttpStatus.INTERNAL_SERVER_ERROR;
    };
  }
}
