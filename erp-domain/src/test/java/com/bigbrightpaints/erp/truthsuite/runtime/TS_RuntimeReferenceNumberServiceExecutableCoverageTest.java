package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.service.NumberSequenceService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import java.time.LocalDate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("concurrency")
@Tag("reconciliation")
class TS_RuntimeReferenceNumberServiceExecutableCoverageTest {

    @Test
    void reference_generation_is_deterministic_with_timezone_fallback_and_metadata_audit() {
        NumberSequenceService numberSequenceService = mock(NumberSequenceService.class);
        AuditService auditService = mock(AuditService.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        when(numberSequenceService.nextValue(any(Company.class), any(String.class)))
                .thenReturn(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L);

        Company invalidTimezoneCompany = company(10L, "TRUTH", "Invalid/Timezone");
        when(companyClock.today(any(Company.class))).thenReturn(LocalDate.of(2026, 2, 16));
        when(companyClock.today(eq(invalidTimezoneCompany))).thenThrow(new IllegalArgumentException("invalid timezone"));

        ReferenceNumberService service = new ReferenceNumberService(numberSequenceService, auditService, companyClock);
        Dealer dealer = new Dealer();
        dealer.setCode("dealer-001");
        Supplier supplier = new Supplier();
        supplier.setCode("supplier-001");

        assertThat(service.nextJournalReference(invalidTimezoneCompany)).startsWith("JRN-TRUTH-");
        assertThat(service.dealerReceiptReference(invalidTimezoneCompany, dealer)).startsWith("RCPT-DEALER-001-");
        assertThat(service.salesOrderReference(invalidTimezoneCompany, "SO/2026/001")).startsWith("SALE-SO2026001-");
        assertThat(service.supplierPaymentReference(invalidTimezoneCompany, supplier)).startsWith("SUP-SUPPLIER-001-");
        assertThat(service.payrollPaymentReference(invalidTimezoneCompany)).startsWith("PAYROLL-");
        assertThat(service.rawMaterialReceiptReference(invalidTimezoneCompany, "rm:batch/1")).startsWith("RM-RMBATCH1-");
        assertThat(service.costAllocationReference(invalidTimezoneCompany)).startsWith("COST-ALLOC-");
        assertThat(service.invoiceJournalReference(invalidTimezoneCompany)).startsWith("INVJ-TRUTH-");

        assertThat(service.purchaseReference(invalidTimezoneCompany, supplier, "INV-001")).startsWith("RMP-");
        assertThat(service.purchaseReturnReference(invalidTimezoneCompany, supplier)).startsWith("PRN-TRUTH-");
        assertThat(service.inventoryAdjustmentReference(invalidTimezoneCompany, "damaged-return")).startsWith("ADJ-TRUTH-");
        assertThat(service.openingStockReference(invalidTimezoneCompany)).startsWith("OPEN-STOCK-TRUTH-");
        assertThat(service.reversalReference("INV-ABC-0001")).isEqualTo("INV-ABC-0001-REV");

        verify(numberSequenceService, atLeast(10)).nextValue(any(Company.class), any(String.class));
        verify(auditService, atLeast(10)).logSuccess(any(), any());
    }

    @Test
    void purchase_reference_key_is_compacted_and_hashed_to_max_length() {
        ReferenceNumberService service = new ReferenceNumberService(
                mock(NumberSequenceService.class),
                mock(AuditService.class),
                mock(CompanyClock.class)
        );

        Company longCompany = company(20L,
                "COMPANY-CODE-WITH-VERY-LONG-TOKEN-TO-FORCE-COMPACTION-1234567890",
                "UTC");
        Supplier longSupplier = new Supplier();
        longSupplier.setCode("SUPPLIER-CODE-WITH-VERY-LONG-TOKEN-TO-FORCE-COMPACTION-1234567890");

        String key = service.purchaseReferenceKey(
                longCompany,
                longSupplier,
                "INV-WITH-VERY-LONG-MATERIAL-TOKEN-TO-FORCE-COMPACTION-AND-HASH-1234567890"
        );

        assertThat(key).startsWith("RMP-");
        assertThat(key.length()).isLessThanOrEqualTo(59);
    }

    @Test
    void sanitize_fallbacks_use_gen_tokens_for_missing_inputs() {
        NumberSequenceService numberSequenceService = mock(NumberSequenceService.class);
        AuditService auditService = mock(AuditService.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        when(numberSequenceService.nextValue(any(Company.class), any(String.class))).thenReturn(1L, 2L, 3L, 4L);

        ReferenceNumberService service = new ReferenceNumberService(numberSequenceService, auditService, companyClock);

        Company company = company(30L, "", null);
        Dealer dealer = new Dealer();
        dealer.setCode(null);
        Supplier supplier = new Supplier();
        supplier.setCode(null);

        String dealerRef = service.dealerReceiptReference(company, dealer);
        String supplierRef = service.supplierPaymentReference(company, supplier);
        String rmRef = service.rawMaterialReceiptReference(company, null);
        String purchaseKey = service.purchaseReferenceKey(null, null, null);

        assertThat(dealerRef).contains("GEN");
        assertThat(supplierRef).contains("GEN");
        assertThat(rmRef).contains("GEN");
        assertThat(purchaseKey).startsWith("RMP-GEN-GEN-GEN");

        verify(numberSequenceService, atLeast(3)).nextValue(any(Company.class), any(String.class));
        verify(auditService, atLeast(3)).logSuccess(any(), any());
    }

    @Test
    void purchase_key_compaction_covers_small_max_lengths_and_nullable_entities() {
        NumberSequenceService numberSequenceService = mock(NumberSequenceService.class);
        AuditService auditService = mock(AuditService.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        when(numberSequenceService.nextValue(any(), any(String.class))).thenReturn(1L, 2L, 3L);

        ReferenceNumberService service = new ReferenceNumberService(numberSequenceService, auditService, companyClock);

        Company maxSevenCompany = company(41L, "C".repeat(22), "UTC");
        Supplier maxSevenSupplier = new Supplier();
        maxSevenSupplier.setCode("S".repeat(30));
        String sevenKey = service.purchaseReferenceKey(
                maxSevenCompany,
                maxSevenSupplier,
                "INVOICE-TOKEN-FOR-SEVEN-MAX"
        );
        assertThat(sevenKey.length()).isLessThanOrEqualTo(59);

        Company maxEightCompany = company(42L, "D".repeat(20), "UTC");
        Supplier maxEightSupplier = new Supplier();
        maxEightSupplier.setCode("T".repeat(25));
        String eightKey = service.purchaseReferenceKey(
                maxEightCompany,
                maxEightSupplier,
                "INVOICE-TOKEN-FOR-EIGHT-MAX"
        );
        assertThat(eightKey.length()).isLessThanOrEqualTo(59);

        String purchaseReturn = service.purchaseReturnReference(maxEightCompany, null);
        assertThat(purchaseReturn).contains("-GEN-");

        Company blankCodeCompany = new Company();
        blankCodeCompany.setCode(" ");
        blankCodeCompany.setTimezone("UTC");
        String openingStock = service.openingStockReference(blankCodeCompany);
        assertThat(openingStock).startsWith("OPEN-STOCK-GEN-");

        String openingStockWithNullCompany = service.openingStockReference(null);
        assertThat(openingStockWithNullCompany).startsWith("OPEN-STOCK-GEN-");

        verify(numberSequenceService, atLeast(3)).nextValue(any(), any(String.class));
        verify(auditService, atLeast(3)).logSuccess(any(), any());
    }

    private Company company(Long id, String code, String timezone) {
        Company company = new Company();
        company.setCode(code);
        company.setName("Truth Company");
        company.setTimezone(timezone);
        ReflectionTestUtils.setField(company, "id", id);
        return company;
    }
}
