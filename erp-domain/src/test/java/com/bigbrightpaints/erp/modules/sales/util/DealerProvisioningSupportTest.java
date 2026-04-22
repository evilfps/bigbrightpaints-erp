package com.bigbrightpaints.erp.modules.sales.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class DealerProvisioningSupportTest {

  @Test
  void resolveStatusForOnboarding_preservesNonActiveStatuses() {
    for (String status : List.of("ON_HOLD", "SUSPENDED", "BLOCKED")) {
      assertThat(DealerProvisioningSupport.resolveStatusForOnboarding(status)).isEqualTo(status);
      assertThat(DealerProvisioningSupport.resolveStatusForOnboarding(" " + status + " "))
          .isEqualTo(status);
      assertThat(DealerProvisioningSupport.resolveStatusForOnboarding(status.toLowerCase()))
          .isEqualTo(status);
    }
  }

  @Test
  void resolveStatusForOnboarding_defaultsToActiveForNonPreservedStatuses() {
    assertThat(DealerProvisioningSupport.resolveStatusForOnboarding(null)).isEqualTo("ACTIVE");
    assertThat(DealerProvisioningSupport.resolveStatusForOnboarding(" ")).isEqualTo("ACTIVE");
    assertThat(DealerProvisioningSupport.resolveStatusForOnboarding("INACTIVE"))
        .isEqualTo("ACTIVE");
    assertThat(DealerProvisioningSupport.resolveStatusForOnboarding("UNKNOWN")).isEqualTo("ACTIVE");
  }

  @Test
  void isPreservedNonActiveStatus_matchesOnlyProtectedStatuses() {
    assertThat(DealerProvisioningSupport.isPreservedNonActiveStatus("on_hold")).isTrue();
    assertThat(DealerProvisioningSupport.isPreservedNonActiveStatus(" suspended ")).isTrue();
    assertThat(DealerProvisioningSupport.isPreservedNonActiveStatus("BLOCKED")).isTrue();
    assertThat(DealerProvisioningSupport.isPreservedNonActiveStatus("ACTIVE")).isFalse();
    assertThat(DealerProvisioningSupport.isPreservedNonActiveStatus("INACTIVE")).isFalse();
    assertThat(DealerProvisioningSupport.isPreservedNonActiveStatus(null)).isFalse();
  }
}
