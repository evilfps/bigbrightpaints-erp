package com.bigbrightpaints.erp.core.util;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyHeaderUtilsTest {

    @Test
    void resolveHeaderKey_prefersPrimaryHeader() {
        String resolved = IdempotencyHeaderUtils.resolveHeaderKey("hdr-001", "hdr-001");
        assertThat(resolved).isEqualTo("hdr-001");
    }

    @Test
    void resolveHeaderKey_usesLegacyWhenPrimaryMissing() {
        String resolved = IdempotencyHeaderUtils.resolveHeaderKey(null, "legacy-001");
        assertThat(resolved).isEqualTo("legacy-001");
    }

    @Test
    void resolveHeaderKey_rejectsPrimaryLegacyMismatch() {
        assertThatThrownBy(() -> IdempotencyHeaderUtils.resolveHeaderKey("hdr-001", "legacy-001"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key header mismatch");
    }

    @Test
    void resolveBodyOrHeaderKey_rejectsBodyHeaderMismatch() {
        assertThatThrownBy(() -> IdempotencyHeaderUtils.resolveBodyOrHeaderKey("body-001", "hdr-001", null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch");
    }

    @Test
    void resolveBodyOrHeaderKey_returnsBodyKeyWhenMatchingHeader() {
        String resolved = IdempotencyHeaderUtils.resolveBodyOrHeaderKey(" body-001 ", "body-001", null);
        assertThat(resolved).isEqualTo("body-001");
    }
}
