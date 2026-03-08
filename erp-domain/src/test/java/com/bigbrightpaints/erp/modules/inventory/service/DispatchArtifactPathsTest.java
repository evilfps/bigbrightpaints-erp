package com.bigbrightpaints.erp.modules.inventory.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DispatchArtifactPathsTest {

    @Test
    void deliveryChallanNumber_handlesBlankAndTrimmedSlipNumbers() {
        assertThat(DispatchArtifactPaths.deliveryChallanNumber(null)).isNull();
        assertThat(DispatchArtifactPaths.deliveryChallanNumber("   ")).isNull();
        assertThat(DispatchArtifactPaths.deliveryChallanNumber(" PS-100 ")).isEqualTo("DC-PS-100");
    }

    @Test
    void deliveryChallanPdfPath_handlesNullAndBuildsCanonicalPath() {
        assertThat(DispatchArtifactPaths.deliveryChallanPdfPath(null)).isNull();
        assertThat(DispatchArtifactPaths.deliveryChallanPdfPath(77L)).isEqualTo("/api/v1/dispatch/slip/77/challan/pdf");
    }
}
