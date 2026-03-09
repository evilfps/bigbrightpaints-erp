package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationApplication;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierSettlementRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementServiceTest {

    @Test
    void recordSupplierPayment_normalizesTrimmedTextAndAmount() {
        AccountingIdempotencyService idempotencyService = mock(AccountingIdempotencyService.class);
        SettlementService service = new SettlementService(idempotencyService);
        SupplierPaymentRequest normalized = new SupplierPaymentRequest(
                11L,
                22L,
                new BigDecimal("25.00"),
                "PAY-001",
                "memo",
                "idem-001",
                List.of()
        );
        when(idempotencyService.recordSupplierPayment(any())).thenReturn(null);

        service.recordSupplierPayment(new SupplierPaymentRequest(
                11L,
                22L,
                new BigDecimal("25.00"),
                "  PAY-001  ",
                "  memo  ",
                "  idem-001  ",
                List.of()
        ));

        verify(idempotencyService).recordSupplierPayment(normalized);
    }

    @Test
    void settleDealerInvoices_keepsNullAmountAndRetainsUnappliedApplication() {
        AccountingIdempotencyService idempotencyService = mock(AccountingIdempotencyService.class);
        SettlementService service = new SettlementService(idempotencyService);
        PartnerSettlementResponse expected = new PartnerSettlementResponse(
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of()
        );
        DealerSettlementRequest normalized = new DealerSettlementRequest(
                15L,
                26L,
                null,
                null,
                null,
                null,
                null,
                SettlementAllocationApplication.FUTURE_APPLICATION,
                LocalDate.of(2026, 3, 10),
                null,
                null,
                null,
                false,
                List.of(),
                null
        );
        when(idempotencyService.settleDealerInvoices(any())).thenReturn(expected);

        PartnerSettlementResponse actual = service.settleDealerInvoices(new DealerSettlementRequest(
                15L,
                26L,
                null,
                null,
                null,
                null,
                null,
                SettlementAllocationApplication.FUTURE_APPLICATION,
                LocalDate.of(2026, 3, 10),
                "   ",
                "   ",
                "   ",
                null,
                List.of(),
                null
        ));

        assertThat(actual).isEqualTo(expected);
        verify(idempotencyService).settleDealerInvoices(normalized);
    }

    @Test
    void settleSupplierInvoices_normalizesDocumentApplicationToNull() {
        AccountingIdempotencyService idempotencyService = mock(AccountingIdempotencyService.class);
        SettlementService service = new SettlementService(idempotencyService);
        SupplierSettlementRequest normalized = new SupplierSettlementRequest(
                19L,
                28L,
                null,
                null,
                null,
                null,
                new BigDecimal("10.00"),
                null,
                LocalDate.of(2026, 3, 11),
                "SUP-SET-1",
                "memo",
                "idem-1",
                true,
                List.of()
        );
        when(idempotencyService.settleSupplierInvoices(any())).thenReturn(null);

        service.settleSupplierInvoices(new SupplierSettlementRequest(
                19L,
                28L,
                null,
                null,
                null,
                null,
                new BigDecimal("10.00"),
                SettlementAllocationApplication.DOCUMENT,
                LocalDate.of(2026, 3, 11),
                " SUP-SET-1 ",
                " memo ",
                " idem-1 ",
                true,
                List.of()
        ));

        verify(idempotencyService).settleSupplierInvoices(normalized);
    }

    @Test
    void autoSettleSupplier_generatesDeterministicReferenceAndIdempotencyWhenMissing() {
        AccountingIdempotencyService idempotencyService = mock(AccountingIdempotencyService.class);
        SettlementService service = new SettlementService(idempotencyService);
        BigDecimal amount = new BigDecimal("12.50");
        String seed = "SUPPLIER|33|null|12.5";
        String deterministicSuffix = IdempotencyUtils.sha256Hex(seed, 24).toUpperCase(Locale.ROOT);
        String expectedReference = "SUP-SET-" + deterministicSuffix.substring(0, 12);
        AutoSettlementRequest normalized = new AutoSettlementRequest(
                null,
                amount,
                expectedReference,
                null,
                expectedReference
        );
        when(idempotencyService.autoSettleSupplier(any(), any())).thenReturn(null);

        service.autoSettleSupplier(33L, new AutoSettlementRequest(null, amount, null, "   ", null));

        verify(idempotencyService).autoSettleSupplier(33L, normalized);
    }
}
