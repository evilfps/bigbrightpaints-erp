package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountingControllerExportGovernanceContractTest {

    @Test
    void auditDigestEndpoints_requireAdminAuthorityAnnotation() throws NoSuchMethodException {
        Method digest = AccountingController.class.getMethod("auditDigest", String.class, String.class);
        Method digestCsv = AccountingController.class.getMethod("auditDigestCsv", String.class, String.class);

        assertThat(digest.getAnnotation(PreAuthorize.class)).isNotNull();
        assertThat(digest.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ROLE_ADMIN')");
        assertThat(digestCsv.getAnnotation(PreAuthorize.class)).isNotNull();
        assertThat(digestCsv.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ROLE_ADMIN')");
    }

    @Test
    void auditDigestCsv_logsDeterministicDataExportEvidence() {
        AccountingService accountingService = mock(AccountingService.class);
        AuditService auditService = mock(AuditService.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        when(accountingService.auditDigestCsv(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .thenReturn("h1,h2\nv1,v2");

        AccountingController controller = new AccountingController(
                accountingService,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                companyContextService,
                companyClock
        );
        ReflectionTestUtils.setField(controller, "auditService", auditService);

        ResponseEntity<String> response = controller.auditDigestCsv("2026-01-01", "2026-01-31");

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=audit-digest.csv");
        assertThat(response.getBody()).isEqualTo("h1,h2\nv1,v2");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.DATA_EXPORT), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("resourceType", "ACCOUNTING_AUDIT_DIGEST")
                .containsEntry("resourceId", "")
                .containsEntry("operation", "EXPORT")
                .containsEntry("format", "csv");
    }
}
