package com.bigbrightpaints.erp.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.core.exception.ApplicationException;

class IdempotencyHeaderUtilsTest {

  @Test
  void resolveHeaderKey_trimsCanonicalHeader() {
    String resolved = IdempotencyHeaderUtils.resolveHeaderKey("  hdr-001  ");
    assertThat(resolved).isEqualTo("hdr-001");
  }

  @Test
  void resolveHeaderKey_treatsBlankHeaderAsMissing() {
    assertThat(IdempotencyHeaderUtils.resolveHeaderKey("   ")).isNull();
  }

  @Test
  void resolveBodyOrHeaderKey_rejectsBodyHeaderMismatch() {
    assertThatThrownBy(() -> IdempotencyHeaderUtils.resolveBodyOrHeaderKey("body-001", "hdr-001"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Idempotency key mismatch");
  }

  @Test
  void resolveBodyOrHeaderKey_returnsBodyKeyWhenMatchingHeader() {
    String resolved = IdempotencyHeaderUtils.resolveBodyOrHeaderKey(" body-001 ", "body-001");
    assertThat(resolved).isEqualTo("body-001");
  }
}
