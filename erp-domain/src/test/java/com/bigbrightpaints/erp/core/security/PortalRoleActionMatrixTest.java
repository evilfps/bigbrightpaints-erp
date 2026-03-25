package com.bigbrightpaints.erp.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class PortalRoleActionMatrixTest {

  @Test
  void resolveAccessDeniedMessage_routesFinancialDispatchNonOwnersToRoleSpecificMessages() {
    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                authentication("ROLE_FACTORY"), request("POST", "/api/v1/sales/dispatch/confirm")))
        .isEqualTo(
            "Use the factory dispatch workspace for prepared-slip lookup and challan details."
                + " Sales must complete the final dispatch posting.");

    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                authentication("ROLE_ACCOUNTING"),
                request("POST", "/api/v1/sales/dispatch/confirm")))
        .isEqualTo(
            "Sales must complete the final dispatch posting from the sales dispatch workspace.");

    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                authentication("ROLE_FACTORY"),
                request("POST", "/api/v1/sales/dispatch/reconcile-order-markers")))
        .isEqualTo(
            "Use the factory dispatch workspace for prepared-slip lookup and challan details."
                + " Sales must complete the final dispatch posting.");

    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                authentication("ROLE_ACCOUNTING"),
                request("POST", "/api/v1/sales/dispatch/reconcile-order-markers")))
        .isEqualTo(
            "Sales must complete the final dispatch posting from the sales dispatch workspace.");
  }

  @Test
  void resolveAccessDeniedMessage_returnsNullForDealerCreditLimitRequestCreation() {
    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                authentication("ROLE_DEALER"),
                request("POST", "/api/v1/dealer-portal/credit-limit-requests")))
        .isNull();
  }

  @Test
  void resolveAccessDeniedMessage_blocksDealerPromotionReads_withTrailingSlashPath() {
    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                authentication("ROLE_DEALER"), request("GET", "/api/v1/sales/promotions/")))
        .isEqualTo("Dealer access is limited to your own portal records and exports.");
  }

  @Test
  void resolveAccessDeniedMessage_routesDispatchWorkspaceAndCreditOverrideDenials() {
    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                authentication("ROLE_SALES"), request("GET", "/api/v1/dispatch/preview/5")))
        .isEqualTo(
            "Use the factory dispatch workspace for prepared-slip lookup and challan details.");

    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                authentication("ROLE_FACTORY"),
                request("POST", "/api/v1/credit/override-requests/77/approve")))
        .isEqualTo("An admin or accountant must review this credit limit override request.");

    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                authentication("ROLE_SALES"),
                request("POST", "/api/v1/credit/override-requests/77/approve")))
        .isEqualTo("An admin or accountant must review this credit limit override request.");
  }

  @Test
  void resolveAccessDeniedMessage_returnsNullForUnmappedRequestsOrMissingInputs() {
    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                null, request("GET", "/api/v1/private")))
        .isNull();
    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(authentication("ROLE_ADMIN"), null))
        .isNull();
    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                authentication("ROLE_ADMIN"), request("GET", "/api/v1/admin/settings")))
        .isNull();
  }

  @Test
  void resolveAccessDeniedMessage_returnsNullWhenAuthoritiesOrMethodDoNotMatch() {
    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                authenticationWithNullAuthorities(),
                request("POST", "/api/v1/sales/dispatch/confirm")))
        .isNull();

    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                authentication("ROLE_DEALER"),
                request("GET", "/api/v1/dealer-portal/credit-limit-requests")))
        .isNull();

    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                authentication("ROLE_ADMIN"),
                request("POST", "/api/v1/dealer-portal/credit-limit-requests")))
        .isNull();

    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                authentication("ROLE_ADMIN"), request("GET", "/api/v1/sales/promotions")))
        .isNull();

    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                authentication("ROLE_ADMIN"), request("POST", "   ")))
        .isNull();

    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                authentication("ROLE_ADMIN"), request("POST", "/api/v1/sales/dispatch/confirm")))
        .isNull();

    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                authentication("ROLE_ADMIN"), request("GET", "/api/v1/dispatch/preview/5")))
        .isNull();

    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                authentication("ROLE_ADMIN"),
                request("POST", "/api/v1/credit/override-requests/77/approve")))
        .isNull();
  }

  @Test
  void businessFriendlyDispatchMessagesRemainStable() {
    assertThat(PortalRoleActionMatrix.transporterOrDriverRequiredMessage())
        .isEqualTo("Add the transporter name or driver name before confirming dispatch.");
    assertThat(PortalRoleActionMatrix.vehicleNumberRequiredMessage())
        .isEqualTo("Add the vehicle number before confirming dispatch.");
    assertThat(PortalRoleActionMatrix.challanReferenceRequiredMessage())
        .isEqualTo("Add the challan reference before confirming dispatch.");
  }

  @Test
  void resolveAccessDeniedMessage_normalizesRepeatedTrailingSlashes() {
    assertThat(
            PortalRoleActionMatrix.resolveAccessDeniedMessage(
                authentication("ROLE_DEALER"), request("GET", "/api/v1/sales/promotions///")))
        .isEqualTo("Dealer access is limited to your own portal records and exports.");
  }

  private Authentication authentication(String... authorities) {
    return new UsernamePasswordAuthenticationToken(
        "user@bbp.com",
        "N/A",
        List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
  }

  private MockHttpServletRequest request(String method, String path) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setRequestURI(path);
    return request;
  }

  private Authentication authenticationWithNullAuthorities() {
    return new Authentication() {
      @Override
      public List<? extends GrantedAuthority> getAuthorities() {
        return null;
      }

      @Override
      public Object getCredentials() {
        return "N/A";
      }

      @Override
      public Object getDetails() {
        return null;
      }

      @Override
      public Object getPrincipal() {
        return "user@bbp.com";
      }

      @Override
      public boolean isAuthenticated() {
        return true;
      }

      @Override
      public void setAuthenticated(boolean isAuthenticated) {
        throw new UnsupportedOperationException();
      }

      @Override
      public String getName() {
        return "user@bbp.com";
      }
    };
  }
}
