package com.bigbrightpaints.erp.truthsuite.accounting;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.accounting.controller.AccountingController;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.DealerReceiptService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@Tag("critical")
@Tag("reconciliation")
@Tag("concurrency")
class TS_AccountingControllerIdempotencyHeaderParityRuntimeCoverageTest {

    @Test
    void recordDealerReceipt_noBodyAndNoHeaders_keepsOriginalRequest() {
        DealerReceiptService dealerReceiptService = mock(DealerReceiptService.class);
        AccountingController controller = newController(dealerReceiptService);
        DealerReceiptRequest request = dealerReceiptRequest(null);
        when(dealerReceiptService.recordDealerReceipt(any())).thenReturn(null);

        controller.recordDealerReceipt(request, null, null);

        ArgumentCaptor<DealerReceiptRequest> captor = ArgumentCaptor.forClass(DealerReceiptRequest.class);
        verify(dealerReceiptService).recordDealerReceipt(captor.capture());
        assertThat(captor.getValue()).isSameAs(request);
        assertThat(captor.getValue().idempotencyKey()).isNull();
    }

    @Test
    void recordDealerReceipt_appliesHeaderKeyWhenBodyMissing() {
        DealerReceiptService dealerReceiptService = mock(DealerReceiptService.class);
        AccountingController controller = newController(dealerReceiptService);
        DealerReceiptRequest request = dealerReceiptRequest(null);
        when(dealerReceiptService.recordDealerReceipt(any())).thenReturn(null);

        controller.recordDealerReceipt(request, "hdr-001", null);

        ArgumentCaptor<DealerReceiptRequest> captor = ArgumentCaptor.forClass(DealerReceiptRequest.class);
        verify(dealerReceiptService).recordDealerReceipt(captor.capture());
        assertThat(captor.getValue()).isNotSameAs(request);
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("hdr-001");
    }

    @Test
    void recordDealerReceipt_bodyPresent_resolveHeaderOnlyReturnsNullAndKeepsRequest() {
        DealerReceiptService dealerReceiptService = mock(DealerReceiptService.class);
        AccountingController controller = newController(dealerReceiptService);
        DealerReceiptRequest request = dealerReceiptRequest("body-001");
        when(dealerReceiptService.recordDealerReceipt(any())).thenReturn(null);

        controller.recordDealerReceipt(request, "body-001", null);

        ArgumentCaptor<DealerReceiptRequest> captor = ArgumentCaptor.forClass(DealerReceiptRequest.class);
        verify(dealerReceiptService).recordDealerReceipt(captor.capture());
        assertThat(captor.getValue()).isSameAs(request);
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("body-001");
    }

    @Test
    void recordDealerReceipt_primaryLegacyMismatch_throwsApplicationException() {
        DealerReceiptService dealerReceiptService = mock(DealerReceiptService.class);
        AccountingController controller = newController(dealerReceiptService);

        assertThatThrownBy(() -> controller.recordDealerReceipt(
                dealerReceiptRequest(null), "hdr-001", "legacy-001"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch");
        verifyNoInteractions(dealerReceiptService);
    }

    @Test
    void recordDealerReceipt_trimsNonBlankHeaders_andTreatsBlankHeadersAsMissing() {
        DealerReceiptService dealerReceiptService = mock(DealerReceiptService.class);
        AccountingController controller = newController(dealerReceiptService);
        DealerReceiptRequest nonBlankHeaderRequest = dealerReceiptRequest(null);
        DealerReceiptRequest blankHeaderRequest = dealerReceiptRequest(null);
        when(dealerReceiptService.recordDealerReceipt(any())).thenReturn(null);

        controller.recordDealerReceipt(nonBlankHeaderRequest, "  hdr-trim-001  ", "hdr-trim-001");
        controller.recordDealerReceipt(blankHeaderRequest, "   ", "\t");

        ArgumentCaptor<DealerReceiptRequest> captor = ArgumentCaptor.forClass(DealerReceiptRequest.class);
        verify(dealerReceiptService, org.mockito.Mockito.times(2)).recordDealerReceipt(captor.capture());
        List<DealerReceiptRequest> captured = captor.getAllValues();

        assertThat(captured.get(0)).isNotSameAs(nonBlankHeaderRequest);
        assertThat(captured.get(0).idempotencyKey()).isEqualTo("hdr-trim-001");
        assertThat(captured.get(1)).isSameAs(blankHeaderRequest);
        assertThat(captured.get(1).idempotencyKey()).isNull();
    }

    private AccountingController newController(DealerReceiptService dealerReceiptService) {
        return new AccountingController(
                mock(AccountingService.class),
                null,
                dealerReceiptService,
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
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private DealerReceiptRequest dealerReceiptRequest(String idempotencyKey) {
        return new DealerReceiptRequest(
                1001L,
                2001L,
                new BigDecimal("100.00"),
                "RCPT-001",
                "memo",
                idempotencyKey,
                allocations()
        );
    }

    private List<SettlementAllocationRequest> allocations() {
        return List.of(new SettlementAllocationRequest(
                4001L,
                null,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "allocation"
        ));
    }
}
