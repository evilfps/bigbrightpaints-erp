package com.bigbrightpaints.erp.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Field;
import java.math.BigDecimal;
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

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.exception.GlobalExceptionHandler;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingAuditService;

class IntegrationFailureMetadataSchemaContractTest {

  @Test
  void accountingIntegrationFailureMetadataIncludesRequiredSchemaKeys() {
    AuditService auditService = mock(AuditService.class);
    AccountingAuditService service = buildAccountingAuditService(auditService);
    ReflectionTestUtils.setField(service, "strictAccountingEventTrail", false);

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
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
  void accountingPostedEventBestEffortUsesFailureMarkerWhenPayloadValidationFails() {
    AuditService auditService = mock(AuditService.class);
    com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore accountingEventStore =
        mock(com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore.class);
    AccountingAuditService service =
        new AccountingAuditService(
            mock(ApplicationEventPublisher.class), auditService, accountingEventStore);
    ReflectionTestUtils.setField(service, "strictAccountingEventTrail", false);

    JournalEntry journalEntry = new JournalEntry();
    journalEntry.setReferenceNumber("JRN-OVERSIZE-1");
    journalEntry.setMemo("x".repeat(600));
    JournalLine line = new JournalLine();
    Account account = new Account();
    account.setCode("4000");
    line.setAccount(account);
    line.setDescription("ok");
    line.setDebit(BigDecimal.ONE);
    journalEntry.addLine(line);

    boolean recorded =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            service, "recordJournalEntryPostedEventSafe", journalEntry, Map.of());

    assertThat(recorded).isFalse();
    verifyNoInteractions(accountingEventStore);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), metadataCaptor.capture());
    assertThat(metadataCaptor.getValue())
        .containsEntry("eventTrailOperation", "JOURNAL_ENTRY_POSTED")
        .containsEntry("policy", "BEST_EFFORT")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "VALIDATION");
  }

  @Test
  void dealerReceiptSettlementEventBestEffortUsesFailureMarkerWhenPayloadValidationFails() {
    AuditService auditService = mock(AuditService.class);
    com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore accountingEventStore =
        mock(com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore.class);
    AccountingAuditService service =
        new AccountingAuditService(
            mock(ApplicationEventPublisher.class), auditService, accountingEventStore);
    ReflectionTestUtils.setField(service, "strictAccountingEventTrail", false);

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        service,
        "recordDealerReceiptPostedEventSafe",
        oversizedSettlementJournalEntry("SETTLE-OVERSIZE-1"),
        10L,
        BigDecimal.ONE,
        "dealer-idem-1");

    verifyNoInteractions(accountingEventStore);
    assertBestEffortFailureMetadata(auditService, "DEALER_RECEIPT_POSTED");
  }

  @Test
  void supplierPaymentSettlementEventBestEffortUsesFailureMarkerWhenPayloadValidationFails() {
    AuditService auditService = mock(AuditService.class);
    com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore accountingEventStore =
        mock(com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore.class);
    AccountingAuditService service =
        new AccountingAuditService(
            mock(ApplicationEventPublisher.class), auditService, accountingEventStore);
    ReflectionTestUtils.setField(service, "strictAccountingEventTrail", false);

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        service,
        "recordSupplierPaymentPostedEventSafe",
        oversizedSettlementJournalEntry("SETTLE-OVERSIZE-2"),
        20L,
        BigDecimal.ONE,
        "supplier-idem-1");

    verifyNoInteractions(accountingEventStore);
    assertBestEffortFailureMetadata(auditService, "SUPPLIER_PAYMENT_POSTED");
  }

  @Test
  void settlementAllocatedEventBestEffortUsesFailureMarkerWhenPayloadValidationFails() {
    AuditService auditService = mock(AuditService.class);
    com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore accountingEventStore =
        mock(com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore.class);
    AccountingAuditService service =
        new AccountingAuditService(
            mock(ApplicationEventPublisher.class), auditService, accountingEventStore);
    ReflectionTestUtils.setField(service, "strictAccountingEventTrail", false);

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        service,
        "recordSettlementAllocatedEventSafe",
        oversizedSettlementJournalEntry("SETTLE-OVERSIZE-3"),
        PartnerType.DEALER,
        30L,
        BigDecimal.ONE,
        1,
        "alloc-idem-1");

    verifyNoInteractions(accountingEventStore);
    assertBestEffortFailureMetadata(auditService, "SETTLEMENT_ALLOCATED");
  }

  @Test
  void globalExceptionIntegrationFailureMetadataIncludesRequiredSchemaKeys() throws Exception {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    setAuditService(handler, mock(AuditService.class));
    setActiveProfile(handler, "dev");

    AuditService auditService = getAuditService(handler);

    MockHttpServletRequest malformedRequest =
        new MockHttpServletRequest("POST", "/api/v1/accounting/settlements/suppliers");
    HttpMessageNotReadableException malformed =
        new HttpMessageNotReadableException(
            "JSON parse error", new IllegalArgumentException("Unrecognized field"));
    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        handler,
        "handleHttpMessageNotReadable",
        malformed,
        new HttpHeaders(),
        HttpStatus.BAD_REQUEST,
        new ServletWebRequest(malformedRequest));

    MockHttpServletRequest settlementRequest =
        new MockHttpServletRequest("POST", "/api/v1/accounting/settlements/dealers");
    ApplicationException settlementError =
        new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT, "Settlement allocation exceeds outstanding amount");
    handler.handleApplicationException(settlementError, settlementRequest);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(auditService, org.mockito.Mockito.times(2))
        .logFailure(eq(AuditEvent.INTEGRATION_FAILURE), metadataCaptor.capture());
    List<Map<String, String>> emittedMetadata = metadataCaptor.getAllValues();
    assertThat(emittedMetadata).hasSize(2);
    assertThat(emittedMetadata)
        .anySatisfy(
            metadata -> {
              assertRequiredSchema(metadata);
              assertThat(metadata)
                  .containsEntry("category", "request-parse")
                  .containsEntry(
                      IntegrationFailureMetadataSchema.KEY_FAILURE_CODE,
                      IntegrationFailureAlertRoutingPolicy.MALFORMED_REQUEST_FAILURE_CODE)
                  .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "VALIDATION")
                  .containsEntry("requestPath", "/api/v1/accounting/settlements/suppliers");
            });
    assertThat(emittedMetadata)
        .anySatisfy(
            metadata -> {
              assertRequiredSchema(metadata);
              assertThat(metadata)
                  .containsEntry("category", "settlement-failure")
                  .containsEntry(
                      IntegrationFailureMetadataSchema.KEY_FAILURE_CODE,
                      IntegrationFailureAlertRoutingPolicy.SETTLEMENT_OPERATION_FAILURE_CODE)
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
    assertThat(IntegrationFailureMetadataSchema.KEY_OUTSTANDING_AMOUNT)
        .isEqualTo("outstandingAmount");
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
    assertThat(metadata.get(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION))
        .isNotBlank();
    assertThat(metadata.get(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE)).isNotBlank();
  }

  private void assertBestEffortFailureMetadata(AuditService auditService, String operation) {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), metadataCaptor.capture());
    assertThat(metadataCaptor.getValue())
        .containsEntry("eventTrailOperation", operation)
        .containsEntry("policy", "BEST_EFFORT")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "VALIDATION");
  }

  private JournalEntry oversizedSettlementJournalEntry(String referenceNumber) {
    JournalEntry journalEntry = new JournalEntry();
    journalEntry.setReferenceNumber(referenceNumber);
    journalEntry.setMemo("x".repeat(600));
    JournalLine line = new JournalLine();
    Account account = new Account();
    account.setCode("4000");
    line.setAccount(account);
    line.setDescription("ok");
    line.setDebit(BigDecimal.ONE);
    journalEntry.addLine(line);
    return journalEntry;
  }

  private AccountingAuditService buildAccountingAuditService(AuditService auditService) {
    return new AccountingAuditService(
        mock(ApplicationEventPublisher.class),
        auditService,
        mock(com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore.class));
  }

  private static void setActiveProfile(GlobalExceptionHandler handler, String value)
      throws Exception {
    Field field = GlobalExceptionHandler.class.getDeclaredField("activeProfile");
    field.setAccessible(true);
    field.set(handler, value);
  }

  private static void setAuditService(GlobalExceptionHandler handler, AuditService auditService)
      throws Exception {
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
