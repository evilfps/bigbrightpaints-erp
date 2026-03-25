package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.bigbrightpaints.erp.modules.factory.controller.PackingController;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@ActiveProfiles(
    value = {"test", "prod"},
    inheritProfiles = false)
@TestPropertySource(
    properties = {
      "jwt.secret=2f4f8a6c9b1d4e7f8a2c5d9e3f6b7c1a4d8e2f5a9c3b6d7e",
      "spring.mail.host=localhost",
      "spring.mail.username=test-smtp-user",
      "spring.mail.password=test-smtp-password",
      "ERP_LICENSE_KEY=test-license-key",
      "ERP_DISPATCH_DEBIT_ACCOUNT_ID=1",
      "ERP_DISPATCH_CREDIT_ACCOUNT_ID=2",
      "management.endpoint.health.validate-group-membership=false",
      "erp.environment.validation.enabled=false"
    })
class CR_PackingRouteHardCutIT extends AbstractIntegrationTest {

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  @Test
  void packingControllerKeepsOnlyCanonicalPackWritePath() {
    Set<String> packingPaths = collectPathsFor(PackingController.class);

    assertThat(packingPaths).contains("/api/v1/factory/packing-records");
    assertThat(packingPaths).doesNotContain("/api/v1/factory/pack");
    assertThat(packingPaths).doesNotContain("/api/v1/factory/packing-records/{productionLogId}/complete");
  }

  private Set<String> collectPathsFor(Class<?> controllerType) {
    Set<String> paths = new TreeSet<>();
    handlerMapping
        .getHandlerMethods()
        .forEach(
            (mapping, handlerMethod) -> {
              if (!controllerType.equals(handlerMethod.getBeanType())) {
                return;
              }
              paths.addAll(extractPatterns(mapping));
            });
    return paths;
  }

  private Set<String> extractPatterns(RequestMappingInfo mapping) {
    if (mapping.getPathPatternsCondition() != null) {
      return mapping.getPathPatternsCondition().getPatternValues();
    }
    if (mapping.getPatternsCondition() != null) {
      return new TreeSet<>(mapping.getPatternsCondition().getPatterns());
    }
    return Set.of();
  }
}
