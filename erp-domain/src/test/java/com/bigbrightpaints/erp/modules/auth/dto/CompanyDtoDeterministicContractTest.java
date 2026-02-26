package com.bigbrightpaints.erp.modules.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateRequest;
import com.bigbrightpaints.erp.modules.company.dto.CompanyRequest;
import com.bigbrightpaints.erp.modules.company.dto.CompanyTenantMetricsDto;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CompanyDtoDeterministicContractTest {

    @Test
    void companyLifecycleStateDto_accessorsAndValueSemantics_areDeterministic() {
        CompanyLifecycleStateDto dto = new CompanyLifecycleStateDto(
                42L,
                "BBP_MAIN",
                "ACTIVE",
                "HOLD",
                "quota soft limit reached");
        CompanyLifecycleStateDto same = new CompanyLifecycleStateDto(
                42L,
                "BBP_MAIN",
                "ACTIVE",
                "HOLD",
                "quota soft limit reached");
        CompanyLifecycleStateDto different = new CompanyLifecycleStateDto(
                42L,
                "BBP_MAIN",
                "ACTIVE",
                "BLOCKED",
                "quota hard limit reached");

        assertThat(dto.companyId()).isEqualTo(42L);
        assertThat(dto.companyCode()).isEqualTo("BBP_MAIN");
        assertThat(dto.previousLifecycleState()).isEqualTo("ACTIVE");
        assertThat(dto.lifecycleState()).isEqualTo("HOLD");
        assertThat(dto.reason()).isEqualTo("quota soft limit reached");
        assertThat(dto).isEqualTo(same);
        assertThat(dto.hashCode()).isEqualTo(same.hashCode());
        assertThat(dto).isNotEqualTo(different);
    }

    @Test
    void companyLifecycleStateRequest_accessorsAndValueSemantics_areDeterministic() {
        CompanyLifecycleStateRequest request = new CompanyLifecycleStateRequest("BLOCKED", "manual review");
        CompanyLifecycleStateRequest same = new CompanyLifecycleStateRequest("BLOCKED", "manual review");
        CompanyLifecycleStateRequest different = new CompanyLifecycleStateRequest("HOLD", "manual review");

        assertThat(request.state()).isEqualTo("BLOCKED");
        assertThat(request.reason()).isEqualTo("manual review");
        assertThat(request).isEqualTo(same);
        assertThat(request.hashCode()).isEqualTo(same.hashCode());
        assertThat(request).isNotEqualTo(different);
    }

    @Test
    void companyRequest_canonicalConstructor_populatesAllFields() {
        CompanyRequest request = new CompanyRequest(
                "Big Bright Paints",
                "BBP_MAIN",
                "Asia/Kolkata",
                new BigDecimal("18.00"),
                120L,
                3_000L,
                2_097_152L,
                7L,
                Boolean.TRUE,
                Boolean.FALSE);
        CompanyRequest same = new CompanyRequest(
                "Big Bright Paints",
                "BBP_MAIN",
                "Asia/Kolkata",
                new BigDecimal("18.00"),
                120L,
                3_000L,
                2_097_152L,
                7L,
                Boolean.TRUE,
                Boolean.FALSE);
        CompanyRequest different = new CompanyRequest(
                "Big Bright Paints",
                "BBP_MAIN",
                "Asia/Kolkata",
                new BigDecimal("19.00"),
                120L,
                3_000L,
                2_097_152L,
                7L,
                Boolean.TRUE,
                Boolean.FALSE);

        assertThat(request.name()).isEqualTo("Big Bright Paints");
        assertThat(request.code()).isEqualTo("BBP_MAIN");
        assertThat(request.timezone()).isEqualTo("Asia/Kolkata");
        assertThat(request.defaultGstRate()).isEqualByComparingTo("18.00");
        assertThat(request.quotaMaxActiveUsers()).isEqualTo(120L);
        assertThat(request.quotaMaxApiRequests()).isEqualTo(3_000L);
        assertThat(request.quotaMaxStorageBytes()).isEqualTo(2_097_152L);
        assertThat(request.quotaMaxConcurrentSessions()).isEqualTo(7L);
        assertThat(request.quotaSoftLimitEnabled()).isTrue();
        assertThat(request.quotaHardLimitEnabled()).isFalse();
        assertThat(request).isEqualTo(same);
        assertThat(request.hashCode()).isEqualTo(same.hashCode());
        assertThat(request).isNotEqualTo(different);
    }

    @Test
    void companyRequest_shortConstructor_defaultsQuotaEnvelopeToNull() {
        CompanyRequest request = new CompanyRequest(
                "Big Bright Paints",
                "BBP_MAIN",
                "Asia/Kolkata",
                new BigDecimal("18.00"));

        assertThat(request.name()).isEqualTo("Big Bright Paints");
        assertThat(request.code()).isEqualTo("BBP_MAIN");
        assertThat(request.timezone()).isEqualTo("Asia/Kolkata");
        assertThat(request.defaultGstRate()).isEqualByComparingTo("18.00");
        assertThat(request.quotaMaxActiveUsers()).isNull();
        assertThat(request.quotaMaxApiRequests()).isNull();
        assertThat(request.quotaMaxStorageBytes()).isNull();
        assertThat(request.quotaMaxConcurrentSessions()).isNull();
        assertThat(request.quotaSoftLimitEnabled()).isNull();
        assertThat(request.quotaHardLimitEnabled()).isNull();
    }

    @Test
    void companyRequest_minimalBootstrapConstructor_keepsOptionalFieldsNull() {
        CompanyRequest request = new CompanyRequest(
                "Big Bright Paints",
                "BBP_MAIN",
                "Asia/Kolkata");

        assertThat(request.name()).isEqualTo("Big Bright Paints");
        assertThat(request.code()).isEqualTo("BBP_MAIN");
        assertThat(request.timezone()).isEqualTo("Asia/Kolkata");
        assertThat(request.defaultGstRate()).isNull();
        assertThat(request.quotaMaxActiveUsers()).isNull();
        assertThat(request.quotaMaxApiRequests()).isNull();
        assertThat(request.quotaMaxStorageBytes()).isNull();
        assertThat(request.quotaMaxConcurrentSessions()).isNull();
        assertThat(request.quotaSoftLimitEnabled()).isNull();
        assertThat(request.quotaHardLimitEnabled()).isNull();
        assertThat(request.firstAdminEmail()).isNull();
        assertThat(request.firstAdminDisplayName()).isNull();
    }

    @Test
    void companyTenantMetricsDto_accessorsAndValueSemantics_areDeterministic() {
        CompanyTenantMetricsDto metrics = new CompanyTenantMetricsDto(
                42L,
                "BBP_MAIN",
                "ACTIVE",
                "within quota",
                120L,
                3_000L,
                2_097_152L,
                7L,
                true,
                false,
                110L,
                1_800L,
                15L,
                83L,
                5L,
                1_572_864L);
        CompanyTenantMetricsDto same = new CompanyTenantMetricsDto(
                42L,
                "BBP_MAIN",
                "ACTIVE",
                "within quota",
                120L,
                3_000L,
                2_097_152L,
                7L,
                true,
                false,
                110L,
                1_800L,
                15L,
                83L,
                5L,
                1_572_864L);
        CompanyTenantMetricsDto different = new CompanyTenantMetricsDto(
                42L,
                "BBP_MAIN",
                "ACTIVE",
                "within quota",
                120L,
                3_000L,
                2_097_152L,
                7L,
                true,
                false,
                110L,
                1_800L,
                16L,
                88L,
                5L,
                1_572_864L);

        assertThat(metrics.companyId()).isEqualTo(42L);
        assertThat(metrics.companyCode()).isEqualTo("BBP_MAIN");
        assertThat(metrics.lifecycleState()).isEqualTo("ACTIVE");
        assertThat(metrics.lifecycleReason()).isEqualTo("within quota");
        assertThat(metrics.quotaMaxActiveUsers()).isEqualTo(120L);
        assertThat(metrics.quotaMaxApiRequests()).isEqualTo(3_000L);
        assertThat(metrics.quotaMaxStorageBytes()).isEqualTo(2_097_152L);
        assertThat(metrics.quotaMaxConcurrentSessions()).isEqualTo(7L);
        assertThat(metrics.quotaSoftLimitEnabled()).isTrue();
        assertThat(metrics.quotaHardLimitEnabled()).isFalse();
        assertThat(metrics.activeUserCount()).isEqualTo(110L);
        assertThat(metrics.apiActivityCount()).isEqualTo(1_800L);
        assertThat(metrics.apiErrorCount()).isEqualTo(15L);
        assertThat(metrics.apiErrorRateInBasisPoints()).isEqualTo(83L);
        assertThat(metrics.distinctSessionCount()).isEqualTo(5L);
        assertThat(metrics.auditStorageBytes()).isEqualTo(1_572_864L);
        assertThat(metrics).isEqualTo(same);
        assertThat(metrics.hashCode()).isEqualTo(same.hashCode());
        assertThat(metrics).isNotEqualTo(different);
    }
}
