package com.bigbrightpaints.erp.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import io.jsonwebtoken.Claims;

class AccessDeniedAuditMarkerTest {

  @AfterEach
  void clearCompanyContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void resolveTenantScope_prefersBoundCompanyContextOverRequestData() {
    CompanyContextHolder.setCompanyCode("BOUND-TENANT");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Company-Code", "FORGED-TENANT");
    request.setAttribute("jwtClaims", claimsWithCompanyCode("JWT-TENANT"));

    String resolved = AccessDeniedAuditMarker.resolveTenantScope(request);

    assertThat(resolved).isEqualTo("BOUND-TENANT");
  }

  @Test
  void resolveTenantScope_usesJwtCompanyCodeWhenBoundContextMissing() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Company-Code", "FORGED-TENANT");
    request.setAttribute("jwtClaims", claimsWithCompanyCode("JWT-TENANT"));

    String resolved = AccessDeniedAuditMarker.resolveTenantScope(request);

    assertThat(resolved).isEqualTo("JWT-TENANT");
  }

  @Test
  void resolveTenantScope_doesNotTrustHeaderOnlyTenantScope() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Company-Code", "HEADER-ONLY-TENANT");

    String resolved = AccessDeniedAuditMarker.resolveTenantScope(request);

    assertThat(resolved).isNull();
  }

  private Claims claimsWithCompanyCode(String companyCode) {
    Claims claims = mock(Claims.class);
    when(claims.get("companyCode", String.class)).thenReturn(companyCode);
    return claims;
  }
}
