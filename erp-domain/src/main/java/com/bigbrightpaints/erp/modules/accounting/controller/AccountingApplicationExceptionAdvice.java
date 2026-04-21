package com.bigbrightpaints.erp.modules.accounting.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.exception.GlobalExceptionHandler;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice(basePackages = "com.bigbrightpaints.erp.modules.accounting.controller")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccountingApplicationExceptionAdvice {

  private final GlobalExceptionHandler globalExceptionHandler;

  @Autowired
  public AccountingApplicationExceptionAdvice(GlobalExceptionHandler globalExceptionHandler) {
    this.globalExceptionHandler = globalExceptionHandler;
  }

  @ExceptionHandler(ApplicationException.class)
  public ResponseEntity<ApiResponse<Map<String, Object>>> handleApplicationException(
      ApplicationException ex, HttpServletRequest request) {
    ErrorCode errorCode = ex != null ? ex.getErrorCode() : null;
    HttpStatus status =
        errorCode == ErrorCode.BUSINESS_ENTITY_NOT_FOUND || errorCode == ErrorCode.FILE_NOT_FOUND
            ? HttpStatus.NOT_FOUND
            : errorCode != null && errorCode.getCode().startsWith("CONC_")
                ? AccountingApplicationExceptionResponses.determineHttpStatus(errorCode)
                : HttpStatus.BAD_REQUEST;
    return globalExceptionHandler.buildApplicationExceptionResponse(ex, request, status);
  }
}
