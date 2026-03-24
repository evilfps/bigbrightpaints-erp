package com.bigbrightpaints.erp.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.exception.GlobalExceptionHandler;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalReferenceResolver;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.accounting.service.SupplierLedgerService;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.ServletWebRequest;

class IntegrationFailureMetadataSchemaContractTest {

    @Test
    void accountingIntegrationFailureMetadataIncludesRequiredSchemaKeys() {
        AuditService auditService = mock(AuditService.class);
        AccountingService service = buildAccountingService(auditService);
        ReflectionTestUtils.setField(service, "strictAccountingEventTrail", false);

        ReflectionTestUtils.invokeMethod(
                service,
                "handleAccountingEventTrailFailure",
                "JOURNAL_ENTRY_POSTED",
                "JRN-SCHEMA-1",
                new IllegalStateException("event-store-down"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), metadataCaptor.capture());
        assertRequiredSchema(metadataCaptor.getValue());
    }

    @Test
    void globalExceptionIntegrationFailureMetadataIncludesRequiredSchemaKeys() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setAuditService(handler, mock(AuditService.class));
        setActiveProfile(handler, "dev");

        AuditService auditService = getAuditService(handler);

        MockHttpServletRequest malformedRequest = new MockHttpServletRequest("POST", "/api/v1/accounting/settlements/suppliers");
        HttpMessageNotReadableException malformed = new HttpMessageNotReadableException(
                "JSON parse error", new IllegalArgumentException("Unrecognized field"));
        ReflectionTestUtils.invokeMethod(
                handler,
                "handleHttpMessageNotReadable",
                malformed,
                new HttpHeaders(),
                HttpStatus.BAD_REQUEST,
                new ServletWebRequest(malformedRequest));

        MockHttpServletRequest settlementRequest = new MockHttpServletRequest("POST", "/api/v1/accounting/settlements/dealers");
        ApplicationException settlementError = new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT,
                "Settlement allocation exceeds outstanding amount");
        handler.handleApplicationException(settlementError, settlementRequest);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService, org.mockito.Mockito.times(2))
                .logFailure(eq(AuditEvent.INTEGRATION_FAILURE), metadataCaptor.capture());
        List<Map<String, String>> emittedMetadata = metadataCaptor.getAllValues();
        assertThat(emittedMetadata).hasSize(2);
        assertThat(emittedMetadata)
                .anySatisfy(metadata -> {
                    assertRequiredSchema(metadata);
                    assertThat(metadata)
                            .containsEntry("category", "request-parse")
                            .containsEntry(IntegrationFailureMetadataSchema.KEY_FAILURE_CODE, IntegrationFailureAlertRoutingPolicy.MALFORMED_REQUEST_FAILURE_CODE)
                            .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "VALIDATION")
                            .containsEntry("requestPath", "/api/v1/accounting/settlements/suppliers");
                });
        assertThat(emittedMetadata)
                .anySatisfy(metadata -> {
                    assertRequiredSchema(metadata);
                    assertThat(metadata)
                            .containsEntry("category", "settlement-failure")
                            .containsEntry(IntegrationFailureMetadataSchema.KEY_FAILURE_CODE, IntegrationFailureAlertRoutingPolicy.SETTLEMENT_OPERATION_FAILURE_CODE)
                            .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "VALIDATION")
                            .containsEntry("settlementType", "DEALER");
                });
    }

    @Test
    void settlementOptionalMetadataKeyVocabularyIsStable() {
        assertThat(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY).isEqualTo("idempotencyKey");
        assertThat(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE).isEqualTo("partnerType");
        assertThat(IntegrationFailureMetadataSchema.KEY_PARTNER_ID).isEqualTo("partnerId");
        assertThat(IntegrationFailureMetadataSchema.KEY_INVOICE_ID).isEqualTo("invoiceId");
        assertThat(IntegrationFailureMetadataSchema.KEY_PURCHASE_ID).isEqualTo("purchaseId");
        assertThat(IntegrationFailureMetadataSchema.KEY_OUTSTANDING_AMOUNT).isEqualTo("outstandingAmount");
        assertThat(IntegrationFailureMetadataSchema.KEY_APPLIED_AMOUNT).isEqualTo("appliedAmount");
        assertThat(IntegrationFailureMetadataSchema.KEY_ALLOCATION_COUNT).isEqualTo("allocationCount");
        assertThat(IntegrationFailureMetadataSchema.KEY_JOURNAL_ENTRY_ID).isEqualTo("journalEntryId");
        assertThat(IntegrationFailureMetadataSchema.KEY_SETTLEMENT_DATE).isEqualTo("settlementDate");
    }

    private void assertRequiredSchema(Map<String, String> metadata) {
        assertThat(metadata)
                .containsKeys(
                        IntegrationFailureMetadataSchema.KEY_FAILURE_CODE,
                        IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY,
                        IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION,
                        IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE);
        assertThat(metadata.get(IntegrationFailureMetadataSchema.KEY_FAILURE_CODE)).isNotBlank();
        assertThat(metadata.get(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY)).isNotBlank();
        assertThat(metadata.get(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION)).isNotBlank();
        assertThat(metadata.get(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE)).isNotBlank();
    }

    private AccountingService buildAccountingService(AuditService auditService) {
        return new AccountingService(
                mock(CompanyContextService.class),
                mock(AccountRepository.class),
                mock(JournalEntryRepository.class),
                mock(DealerLedgerService.class),
                mock(SupplierLedgerService.class),
                mock(PayrollRunRepository.class),
                mock(PayrollRunLineRepository.class),
                mock(AccountingPeriodService.class),
                mock(ReferenceNumberService.class),
                mock(ApplicationEventPublisher.class),
                mock(CompanyClock.class),
                mock(CompanyEntityLookup.class),
                mock(PartnerSettlementAllocationRepository.class),
                mock(RawMaterialPurchaseRepository.class),
                mock(InvoiceRepository.class),
                mock(RawMaterialMovementRepository.class),
                mock(RawMaterialBatchRepository.class),
                mock(FinishedGoodBatchRepository.class),
                mock(DealerRepository.class),
                mock(SupplierRepository.class),
                mock(InvoiceSettlementPolicy.class),
                mock(JournalReferenceResolver.class),
                mock(JournalReferenceMappingRepository.class),
                mock(EntityManager.class),
                mock(SystemSettingsService.class),
                auditService,
                mock(AccountingEventStore.class),
                mock(com.bigbrightpaints.erp.modules.accounting.service.JournalEntryService.class),
                mock(com.bigbrightpaints.erp.modules.accounting.service.DealerReceiptService.class),
                mock(com.bigbrightpaints.erp.modules.accounting.service.SettlementService.class),
                mock(com.bigbrightpaints.erp.modules.accounting.service.CreditDebitNoteService.class),
                mock(com.bigbrightpaints.erp.modules.accounting.service.AccountingAuditService.class),
                mock(com.bigbrightpaints.erp.modules.accounting.service.InventoryAccountingService.class),
                mock(org.springframework.beans.factory.ObjectProvider.class)
        );
    }

    private static void setActiveProfile(GlobalExceptionHandler handler, String value) throws Exception {
        Field field = GlobalExceptionHandler.class.getDeclaredField("activeProfile");
        field.setAccessible(true);
        field.set(handler, value);
    }

    private static void setAuditService(GlobalExceptionHandler handler, AuditService auditService) throws Exception {
        Field field = GlobalExceptionHandler.class.getDeclaredField("auditService");
        field.setAccessible(true);
        field.set(handler, auditService);
    }

    private static AuditService getAuditService(GlobalExceptionHandler handler) throws Exception {
        Field field = GlobalExceptionHandler.class.getDeclaredField("auditService");
        field.setAccessible(true);
        return (AuditService) field.get(handler);
    }
}
