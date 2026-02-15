package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bigbrightpaints.erp.core.audit.IntegrationFailureMetadataSchema;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("critical")
class TS_RuntimeAccountingReplayConflictExecutableCoverageTest {

    @Test
    void validatePartnerJournalReplay_missingEntry_includesPartnerDetails() {
        AccountingService service = accountingService();
        List<JournalEntryRequest.JournalLineRequest> expectedLines = List.of(
                new JournalEntryRequest.JournalLineRequest(101L, "memo", new BigDecimal("50.00"), BigDecimal.ZERO)
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validatePartnerJournalReplay",
                "IDEM-MISSING",
                PartnerType.DEALER,
                11L,
                "memo",
                null,
                expectedLines,
                "payload mismatch"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> assertReplayConflict(
                        (ApplicationException) throwable,
                        "IDEM-MISSING",
                        "DEALER",
                        11L));
    }

    @Test
    void validatePartnerJournalReplay_dealerMismatch_includesPartnerDetails() {
        AccountingService service = accountingService();
        JournalEntry entry = journalEntryWithDealer(99L, "memo", 101L, "50.00", "0.00");
        List<JournalEntryRequest.JournalLineRequest> expectedLines = List.of(
                new JournalEntryRequest.JournalLineRequest(101L, "memo", new BigDecimal("50.00"), BigDecimal.ZERO)
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validatePartnerJournalReplay",
                "IDEM-DEALER-MISMATCH",
                PartnerType.DEALER,
                11L,
                "memo",
                entry,
                expectedLines,
                "payload mismatch"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> assertReplayConflict(
                        (ApplicationException) throwable,
                        "IDEM-DEALER-MISMATCH",
                        "DEALER",
                        11L));
    }

    @Test
    void validatePartnerJournalReplay_supplierMismatch_includesPartnerDetails() {
        AccountingService service = accountingService();
        JournalEntry entry = journalEntryWithSupplier(88L, "memo", 101L, "50.00", "0.00");
        List<JournalEntryRequest.JournalLineRequest> expectedLines = List.of(
                new JournalEntryRequest.JournalLineRequest(101L, "memo", new BigDecimal("50.00"), BigDecimal.ZERO)
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validatePartnerJournalReplay",
                "IDEM-SUPPLIER-MISMATCH",
                PartnerType.SUPPLIER,
                77L,
                "memo",
                entry,
                expectedLines,
                "payload mismatch"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> assertReplayConflict(
                        (ApplicationException) throwable,
                        "IDEM-SUPPLIER-MISMATCH",
                        "SUPPLIER",
                        77L));
    }

    @Test
    void validatePartnerJournalReplay_unknownPartnerType_includesPartnerDetails() {
        AccountingService service = accountingService();
        JournalEntry entry = journalEntryWithDealer(11L, "memo", 101L, "50.00", "0.00");
        List<JournalEntryRequest.JournalLineRequest> expectedLines = List.of(
                new JournalEntryRequest.JournalLineRequest(101L, "memo", new BigDecimal("50.00"), BigDecimal.ZERO)
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validatePartnerJournalReplay",
                "IDEM-UNKNOWN-TYPE",
                null,
                11L,
                "memo",
                entry,
                expectedLines,
                "payload mismatch"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> assertReplayConflict(
                        (ApplicationException) throwable,
                        "IDEM-UNKNOWN-TYPE",
                        "null",
                        11L));
    }

    @Test
    void validatePartnerJournalReplay_memoMismatch_includesPartnerDetails() {
        AccountingService service = accountingService();
        JournalEntry entry = journalEntryWithDealer(11L, "memo-a", 101L, "50.00", "0.00");
        List<JournalEntryRequest.JournalLineRequest> expectedLines = List.of(
                new JournalEntryRequest.JournalLineRequest(101L, "memo", new BigDecimal("50.00"), BigDecimal.ZERO)
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validatePartnerJournalReplay",
                "IDEM-MEMO-MISMATCH",
                PartnerType.DEALER,
                11L,
                "memo-b",
                entry,
                expectedLines,
                "payload mismatch"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> assertReplayConflict(
                        (ApplicationException) throwable,
                        "IDEM-MEMO-MISMATCH",
                        "DEALER",
                        11L));
    }

    @Test
    void validatePartnerJournalReplay_payloadMismatch_includesPartnerDetails() {
        AccountingService service = accountingService();
        JournalEntry entry = journalEntryWithDealer(11L, "memo", 101L, "50.00", "0.00");
        List<JournalEntryRequest.JournalLineRequest> expectedLines = List.of(
                new JournalEntryRequest.JournalLineRequest(101L, "memo", new BigDecimal("40.00"), BigDecimal.ZERO)
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validatePartnerJournalReplay",
                "IDEM-PAYLOAD-MISMATCH",
                PartnerType.DEALER,
                11L,
                "memo",
                entry,
                expectedLines,
                "payload mismatch"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> assertReplayConflict(
                        (ApplicationException) throwable,
                        "IDEM-PAYLOAD-MISMATCH",
                        "DEALER",
                        11L));
    }

    @Test
    void validateSettlementIdempotencyKey_partnerMismatch_includesPartnerDetails() {
        AccountingService service = accountingService();
        List<PartnerSettlementAllocation> existing = List.of(
                partnerAllocationForDealer(99L, "100.00", "alloc")
        );
        List<SettlementAllocationRequest> requested = List.of(
                new SettlementAllocationRequest(null, null, new BigDecimal("100.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "alloc")
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validateSettlementIdempotencyKey",
                "IDEM-SETTLEMENT-PARTNER-MISMATCH",
                PartnerType.DEALER,
                11L,
                existing,
                requested))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> assertReplayConflict(
                        (ApplicationException) throwable,
                        "IDEM-SETTLEMENT-PARTNER-MISMATCH",
                        "DEALER",
                        11L));
    }

    @Test
    void validateSettlementIdempotencyKey_payloadMismatch_includesPartnerDetails() {
        AccountingService service = accountingService();
        List<PartnerSettlementAllocation> existing = List.of(
                partnerAllocationForSupplier(77L, "100.00", "alloc")
        );
        List<SettlementAllocationRequest> requested = List.of(
                new SettlementAllocationRequest(null, null, new BigDecimal("120.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "alloc")
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validateSettlementIdempotencyKey",
                "IDEM-SETTLEMENT-PAYLOAD-MISMATCH",
                PartnerType.SUPPLIER,
                77L,
                existing,
                requested))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> assertReplayConflict(
                        (ApplicationException) throwable,
                        "IDEM-SETTLEMENT-PAYLOAD-MISMATCH",
                        "SUPPLIER",
                        77L));
    }

    @Test
    void replayConflictDetail_trimsIdempotencyKeyInDetails() {
        AccountingService service = accountingService();
        List<JournalEntryRequest.JournalLineRequest> expectedLines = List.of(
                new JournalEntryRequest.JournalLineRequest(101L, "memo", new BigDecimal("50.00"), BigDecimal.ZERO)
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validatePartnerJournalReplay",
                "  IDEM-TRIM  ",
                PartnerType.DEALER,
                11L,
                "memo",
                null,
                expectedLines,
                "payload mismatch"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> assertReplayConflict(
                        (ApplicationException) throwable,
                        "IDEM-TRIM",
                        "DEALER",
                        11L));
    }

    @Test
    void replayConflictDetail_allowsNullIdempotencyKeyAndPartnerId() {
        AccountingService service = accountingService();
        List<JournalEntryRequest.JournalLineRequest> expectedLines = List.of(
                new JournalEntryRequest.JournalLineRequest(101L, "memo", new BigDecimal("50.00"), BigDecimal.ZERO)
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validatePartnerJournalReplay",
                null,
                PartnerType.DEALER,
                null,
                "memo",
                null,
                expectedLines,
                "payload mismatch"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> {
                    ApplicationException ex = (ApplicationException) throwable;
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
                    assertThat(ex.getDetails())
                            .containsEntry(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, null)
                            .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, "DEALER")
                            .doesNotContainKey(IntegrationFailureMetadataSchema.KEY_PARTNER_ID);
                });
    }

    @Test
    void isJournalEntryPartnerMismatch_coversAllPartnerTypeBranches() {
        AccountingService service = accountingService();
        JournalEntry dealerEntry = journalEntryWithDealer(11L, "memo", 101L, "50.00", "0.00");
        JournalEntry supplierEntry = journalEntryWithSupplier(77L, "memo", 101L, "50.00", "0.00");
        JournalEntry emptyEntry = new JournalEntry();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isJournalEntryPartnerMismatch",
                dealerEntry,
                PartnerType.DEALER,
                12L)).isTrue();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isJournalEntryPartnerMismatch",
                dealerEntry,
                PartnerType.DEALER,
                11L)).isFalse();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isJournalEntryPartnerMismatch",
                supplierEntry,
                PartnerType.SUPPLIER,
                78L)).isTrue();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isJournalEntryPartnerMismatch",
                supplierEntry,
                PartnerType.SUPPLIER,
                77L)).isFalse();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isJournalEntryPartnerMismatch",
                emptyEntry,
                PartnerType.DEALER,
                11L)).isTrue();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isJournalEntryPartnerMismatch",
                dealerEntry,
                null,
                11L)).isTrue();
    }

    @Test
    void isSettlementAllocationPartnerMismatch_coversAllPartnerTypeBranches() {
        AccountingService service = accountingService();
        PartnerSettlementAllocation dealerAllocation = partnerAllocationForDealer(11L, "100.00", "alloc");
        PartnerSettlementAllocation supplierAllocation = partnerAllocationForSupplier(77L, "100.00", "alloc");
        PartnerSettlementAllocation emptyAllocation = new PartnerSettlementAllocation();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isSettlementAllocationPartnerMismatch",
                dealerAllocation,
                PartnerType.DEALER,
                12L)).isTrue();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isSettlementAllocationPartnerMismatch",
                dealerAllocation,
                PartnerType.DEALER,
                11L)).isFalse();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isSettlementAllocationPartnerMismatch",
                supplierAllocation,
                PartnerType.SUPPLIER,
                78L)).isTrue();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isSettlementAllocationPartnerMismatch",
                supplierAllocation,
                PartnerType.SUPPLIER,
                77L)).isFalse();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isSettlementAllocationPartnerMismatch",
                emptyAllocation,
                PartnerType.SUPPLIER,
                77L)).isTrue();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isSettlementAllocationPartnerMismatch",
                dealerAllocation,
                null,
                11L)).isTrue();
    }

    @Test
    void partnerMismatchSubject_returnsCanonicalLabels() {
        AccountingService service = accountingService();

        assertThat((String) ReflectionTestUtils.invokeMethod(
                service,
                "partnerMismatchSubject",
                PartnerType.DEALER)).isEqualTo("dealer");

        assertThat((String) ReflectionTestUtils.invokeMethod(
                service,
                "partnerMismatchSubject",
                PartnerType.SUPPLIER)).isEqualTo("supplier");
    }

    private void assertReplayConflict(ApplicationException ex,
                                      String idempotencyKey,
                                      String partnerType,
                                      Long partnerId) {
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
        assertThat(ex.getDetails())
                .containsEntry(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, idempotencyKey)
                .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, partnerType)
                .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, partnerId);
    }

    private AccountingService accountingService() {
        return new AccountingService(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null
        );
    }

    private JournalEntry journalEntryWithDealer(Long dealerId,
                                                String memo,
                                                Long accountId,
                                                String debit,
                                                String credit) {
        JournalEntry entry = new JournalEntry();
        entry.setDealer(dealer(dealerId));
        entry.setMemo(memo);
        entry.getLines().add(journalLine(accountId, debit, credit));
        return entry;
    }

    private JournalEntry journalEntryWithSupplier(Long supplierId,
                                                  String memo,
                                                  Long accountId,
                                                  String debit,
                                                  String credit) {
        JournalEntry entry = new JournalEntry();
        entry.setSupplier(supplier(supplierId));
        entry.setMemo(memo);
        entry.getLines().add(journalLine(accountId, debit, credit));
        return entry;
    }

    private JournalLine journalLine(Long accountId, String debit, String credit) {
        JournalLine line = new JournalLine();
        line.setAccount(account(accountId));
        line.setDebit(new BigDecimal(debit));
        line.setCredit(new BigDecimal(credit));
        return line;
    }

    private PartnerSettlementAllocation partnerAllocationForDealer(Long dealerId, String appliedAmount, String memo) {
        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        allocation.setDealer(dealer(dealerId));
        allocation.setAllocationAmount(new BigDecimal(appliedAmount));
        allocation.setDiscountAmount(BigDecimal.ZERO);
        allocation.setWriteOffAmount(BigDecimal.ZERO);
        allocation.setFxDifferenceAmount(BigDecimal.ZERO);
        allocation.setMemo(memo);
        return allocation;
    }

    private PartnerSettlementAllocation partnerAllocationForSupplier(Long supplierId, String appliedAmount, String memo) {
        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        allocation.setSupplier(supplier(supplierId));
        allocation.setAllocationAmount(new BigDecimal(appliedAmount));
        allocation.setDiscountAmount(BigDecimal.ZERO);
        allocation.setWriteOffAmount(BigDecimal.ZERO);
        allocation.setFxDifferenceAmount(BigDecimal.ZERO);
        allocation.setMemo(memo);
        return allocation;
    }

    private Account account(Long id) {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    private Dealer dealer(Long id) {
        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", id);
        return dealer;
    }

    private Supplier supplier(Long id) {
        Supplier supplier = new Supplier();
        ReflectionTestUtils.setField(supplier, "id", id);
        return supplier;
    }
}
