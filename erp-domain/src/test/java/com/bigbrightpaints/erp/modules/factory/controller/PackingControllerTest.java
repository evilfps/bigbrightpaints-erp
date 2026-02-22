package com.bigbrightpaints.erp.modules.factory.controller;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.factory.dto.PackingLineRequest;
import com.bigbrightpaints.erp.modules.factory.dto.PackingRequest;
import com.bigbrightpaints.erp.modules.factory.service.BulkPackingService;
import com.bigbrightpaints.erp.modules.factory.service.PackingService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackingControllerTest {

    @Mock
    private PackingService packingService;

    @Mock
    private BulkPackingService bulkPackingService;

    @Test
    void recordPacking_appliesAutoIdempotencyKeyWhenHeaderAndBodyMissing() {
        PackingController controller = new PackingController(packingService, bulkPackingService);
        when(packingService.recordPacking(any())).thenReturn(null);

        PackingRequest request = new PackingRequest(
                1L,
                LocalDate.of(2026, 2, 6),
                "packer",
                null,
                List.of(new PackingLineRequest("10L", new BigDecimal("1"), 1, 1, 1))
        );

        controller.recordPacking(null, null, null, request);

        ArgumentCaptor<PackingRequest> captor = ArgumentCaptor.forClass(PackingRequest.class);
        verify(packingService).recordPacking(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).startsWith("AUTO|FACTORY.PACKING.RECORD|");
    }

    @Test
    void recordPacking_rejectsHeaderBodyMismatch() {
        PackingController controller = new PackingController(packingService, bulkPackingService);

        PackingRequest request = new PackingRequest(
                1L,
                LocalDate.of(2026, 2, 6),
                "packer",
                "body-key",
                List.of(new PackingLineRequest("10L", new BigDecimal("1"), 1, 1, 1))
        );

        assertThatThrownBy(() -> controller.recordPacking("header-key", null, null, request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch");
    }

    @Test
    void recordPacking_appliesHeaderIdempotencyKeyWhenBodyMissing() {
        PackingController controller = new PackingController(packingService, bulkPackingService);
        when(packingService.recordPacking(any())).thenReturn(null);

        PackingRequest request = new PackingRequest(
                1L,
                LocalDate.of(2026, 2, 6),
                "packer",
                null,
                List.of(new PackingLineRequest("10L", new BigDecimal("1"), 1, 1, 1))
        );

        controller.recordPacking("header-key", null, null, request);

        ArgumentCaptor<PackingRequest> captor = ArgumentCaptor.forClass(PackingRequest.class);
        verify(packingService).recordPacking(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("header-key");
    }

    @Test
    void recordPacking_appliesRequestIdFallbackWhenIdempotencyMissing() {
        PackingController controller = new PackingController(packingService, bulkPackingService);
        when(packingService.recordPacking(any())).thenReturn(null);

        PackingRequest request = new PackingRequest(
                1L,
                LocalDate.of(2026, 2, 6),
                "packer",
                null,
                List.of(new PackingLineRequest("10L", new BigDecimal("1"), 1, 1, 1))
        );

        controller.recordPacking(null, null, "req-123", request);

        ArgumentCaptor<PackingRequest> captor = ArgumentCaptor.forClass(PackingRequest.class);
        verify(packingService).recordPacking(captor.capture());
        assertThat(captor.getValue().idempotencyKey())
                .isEqualTo("REQ|FACTORY.PACKING.RECORD|req-123");
    }

    @Test
    void recordPacking_hashesOversizedRequestIdFallbackWithinPersistenceLimit() {
        PackingController controller = new PackingController(packingService, bulkPackingService);
        when(packingService.recordPacking(any())).thenReturn(null);

        PackingRequest request = new PackingRequest(
                1L,
                LocalDate.of(2026, 2, 6),
                "packer",
                null,
                List.of(new PackingLineRequest("10L", new BigDecimal("1"), 1, 1, 1))
        );

        controller.recordPacking(null, null, "req-" + "x".repeat(300), request);

        ArgumentCaptor<PackingRequest> captor = ArgumentCaptor.forClass(PackingRequest.class);
        verify(packingService).recordPacking(captor.capture());
        assertThat(captor.getValue().idempotencyKey())
                .startsWith("REQH|FACTORY.PACKING.RECORD|");
        assertThat(captor.getValue().idempotencyKey().length()).isLessThanOrEqualTo(128);
    }

    @Test
    void recordPacking_appliesLegacyHeaderWhenPrimaryMissing() {
        PackingController controller = new PackingController(packingService, bulkPackingService);
        when(packingService.recordPacking(any())).thenReturn(null);

        PackingRequest request = new PackingRequest(
                1L,
                LocalDate.of(2026, 2, 6),
                "packer",
                null,
                List.of(new PackingLineRequest("10L", new BigDecimal("1"), 1, 1, 1))
        );

        controller.recordPacking(null, "legacy-key", null, request);

        ArgumentCaptor<PackingRequest> captor = ArgumentCaptor.forClass(PackingRequest.class);
        verify(packingService).recordPacking(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("legacy-key");
    }

    @Test
    void recordPacking_rejectsWhenPrimaryLegacyHeadersMismatch() {
        PackingController controller = new PackingController(packingService, bulkPackingService);
        PackingRequest request = new PackingRequest(
                1L,
                LocalDate.of(2026, 2, 6),
                "packer",
                null,
                List.of(new PackingLineRequest("10L", new BigDecimal("1"), 1, 1, 1))
        );

        assertThatThrownBy(() -> controller.recordPacking("header-key", "legacy-key", null, request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch between Idempotency-Key and X-Idempotency-Key headers");
        verifyNoInteractions(packingService, bulkPackingService);
    }
}
