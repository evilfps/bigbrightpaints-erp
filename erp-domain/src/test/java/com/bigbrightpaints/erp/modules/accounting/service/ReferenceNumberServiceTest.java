package com.bigbrightpaints.erp.modules.accounting.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.service.NumberSequenceService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReferenceNumberServiceTest {

    @Mock
    private NumberSequenceService numberSequenceService;
    @Mock
    private AuditService auditService;
    @Mock
    private CompanyClock companyClock;

    private ReferenceNumberService referenceNumberService;

    @BeforeEach
    void setup() {
        referenceNumberService = new ReferenceNumberService(numberSequenceService, auditService, companyClock);
    }

    @Test
    void shouldFormatJournalReferenceWithPadding() {
        Company company = new Company();
        company.setCode("ACME");
        company.setTimezone("UTC");

        when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 3, 2));
        String period = "202603";
        String expectedKey = "JRN-%s-%s".formatted(company.getCode(), period);

        when(numberSequenceService.nextValue(company, expectedKey)).thenReturn(5L);

        String reference = referenceNumberService.nextJournalReference(company);

        assertEquals(expectedKey + "-0005", reference);
        verify(numberSequenceService).nextValue(company, expectedKey);
        verify(auditService).logSuccess(eq(AuditEvent.REFERENCE_GENERATED), anyMap());
    }

    @Test
    void shouldUseSequenceForInventoryAdjustments() {
        Company company = new Company();
        company.setCode("ACME");
        company.setTimezone("UTC");
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        when(numberSequenceService.nextValue(eq(company), keyCaptor.capture())).thenReturn(12L);

        String reference = referenceNumberService.inventoryAdjustmentReference(company, "damaged");

        assertEquals("ADJ-ACME-DAMAGED-0012", reference);
        assertEquals("ADJ-ACME-DAMAGED", keyCaptor.getValue());
        verify(auditService).logSuccess(eq(AuditEvent.REFERENCE_GENERATED), anyMap());
    }

    @Test
    void purchaseReturnReferencesAreCompanyScoped() {
        Company first = new Company();
        first.setCode("F1");
        first.setTimezone("UTC");
        Company second = new Company();
        second.setCode("S2");
        second.setTimezone("UTC");
        Supplier supplier = new Supplier();
        supplier.setCode("sup-01");

        List<String> firstKeys = new ArrayList<>();
        List<String> secondKeys = new ArrayList<>();
        when(numberSequenceService.nextValue(eq(first), anyString())).thenAnswer(invocation -> {
            firstKeys.add(invocation.getArgument(1));
            return 1L;
        });
        when(numberSequenceService.nextValue(eq(second), anyString())).thenAnswer(invocation -> {
            secondKeys.add(invocation.getArgument(1));
            return 1L;
        });

        String firstRef = referenceNumberService.purchaseReturnReference(first, supplier);
        String secondRef = referenceNumberService.purchaseReturnReference(second, supplier);

        assertEquals("PRN-F1-SUP-01-0001", firstRef);
        assertEquals("PRN-S2-SUP-01-0001", secondRef);
        assertEquals(List.of("PRN-F1-SUP-01"), firstKeys);
        assertEquals(List.of("PRN-S2-SUP-01"), secondKeys);
    }

    @Test
    void purchaseReferenceIsBoundedForLongInvoiceNumbers() {
        Company company = new Company();
        company.setCode("CRIT-AXES");
        company.setTimezone("UTC");
        Supplier supplier = new Supplier();
        supplier.setCode("TRAIN-SUP");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        when(numberSequenceService.nextValue(eq(company), keyCaptor.capture())).thenReturn(1L);

        String invoiceNumber = "RECON-BUY-" + UUID.randomUUID();
        String reference = referenceNumberService.purchaseReference(company, supplier, invoiceNumber);

        assertTrue(reference.length() <= 64);
        assertTrue(keyCaptor.getValue().length() <= 59);
    }
}
