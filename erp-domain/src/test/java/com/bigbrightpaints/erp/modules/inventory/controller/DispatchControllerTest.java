package com.bigbrightpaints.erp.modules.inventory.controller;

import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationResponse;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchPreviewDto;
import com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipDto;
import com.bigbrightpaints.erp.modules.inventory.service.DeliveryChallanPdfService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import com.bigbrightpaints.erp.modules.sales.service.SalesDispatchReconciliationService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DispatchControllerTest {

    @Mock
    private FinishedGoodsService finishedGoodsService;

    @Mock
    private SalesDispatchReconciliationService salesDispatchReconciliationService;

    @Mock
    private DeliveryChallanPdfService deliveryChallanPdfService;

    @Test
    void confirmDispatch_callsSalesOnce_andDoesNotDoubleDispatchInventory() {
        DispatchController controller = new DispatchController(
                finishedGoodsService,
                salesDispatchReconciliationService,
                deliveryChallanPdfService);
        Principal principal = () -> "factory.user";
        setFactoryAuthentication();

        DispatchConfirmationRequest request = new DispatchConfirmationRequest(
                10L,
                List.of(new DispatchConfirmationRequest.LineConfirmation(
                        100L,
                        new BigDecimal("2.50"),
                        "Ship as-is"
                )),
                "Dispatch notes",
                null,
                999L,
                "FastMove Logistics",
                "Ayaan",
                "MH12AB1234",
                "LR-7788"
        );

        DispatchConfirmationResponse expected = new DispatchConfirmationResponse(
                10L,
                "PS-10",
                "DISPATCHED",
                Instant.now(),
                "factory.user",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                1L,
                2L,
                List.of(),
                null,
                "FastMove Logistics",
                "Ayaan",
                "MH12AB1234",
                "LR-7788",
                "DC-PS-10",
                "/api/v1/dispatch/slip/10/challan/pdf"
        );
        when(finishedGoodsService.getDispatchConfirmation(10L)).thenReturn(expected);

        ResponseEntity<ApiResponse<DispatchConfirmationResponse>> response = controller.confirmDispatch(request, principal);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data().packagingSlipId()).isEqualTo(expected.packagingSlipId());

        ArgumentCaptor<DispatchConfirmRequest> dispatchCaptor = ArgumentCaptor.forClass(DispatchConfirmRequest.class);
        verify(salesDispatchReconciliationService).confirmDispatch(dispatchCaptor.capture());

        DispatchConfirmRequest dispatchRequest = dispatchCaptor.getValue();
        assertThat(dispatchRequest.packingSlipId()).isEqualTo(10L);
        assertThat(dispatchRequest.confirmedBy()).isEqualTo("factory.user");
        assertThat(dispatchRequest.overrideReason()).isNull();
        assertThat(dispatchRequest.overrideRequestId()).isEqualTo(999L);
        assertThat(dispatchRequest.transporterName()).isEqualTo("FastMove Logistics");
        assertThat(dispatchRequest.driverName()).isEqualTo("Ayaan");
        assertThat(dispatchRequest.vehicleNumber()).isEqualTo("MH12AB1234");
        assertThat(dispatchRequest.challanReference()).isEqualTo("LR-7788");
        assertThat(dispatchRequest.lines()).hasSize(1);
        assertThat(dispatchRequest.lines().get(0).lineId()).isEqualTo(100L);
        assertThat(dispatchRequest.lines().get(0).shipQty()).isEqualByComparingTo("2.50");
        assertThat(dispatchRequest.lines().get(0).notes()).isEqualTo("Ship as-is");

        DispatchConfirmationResponse redacted = response.getBody().data();
        assertThat(redacted.journalEntryId()).isNull();
        assertThat(redacted.cogsJournalEntryId()).isNull();
        assertThat(redacted.totalShippedAmount()).isNull();
        assertThat(redacted.deliveryChallanPdfPath()).isEqualTo("/api/v1/dispatch/slip/10/challan/pdf");

        verify(finishedGoodsService).getDispatchConfirmation(10L);
        verifyNoMoreInteractions(salesDispatchReconciliationService, finishedGoodsService);
        SecurityContextHolder.clearContext();
    }

    @Test
    void factoryViews_areRedactedForPreviewAndSlipDetails() {
        DispatchController controller = new DispatchController(
                finishedGoodsService,
                salesDispatchReconciliationService,
                deliveryChallanPdfService);
        setFactoryAuthentication();

        DispatchPreviewDto preview = new DispatchPreviewDto(
                5L,
                "PS-5",
                "RESERVED",
                7L,
                "SO-7",
                "Dealer",
                "DLR-7",
                Instant.now(),
                new BigDecimal("500.00"),
                new BigDecimal("10.00"),
                new DispatchPreviewDto.GstBreakdown(
                        new BigDecimal("450.00"),
                        new BigDecimal("25.00"),
                        new BigDecimal("25.00"),
                        BigDecimal.ZERO,
                        new BigDecimal("50.00"),
                        new BigDecimal("500.00")),
                List.of(new DispatchPreviewDto.LinePreview(
                        11L,
                        22L,
                        "FG-1",
                        "Primer",
                        "BATCH-1",
                        new BigDecimal("5.00"),
                        new BigDecimal("5.00"),
                        new BigDecimal("5.00"),
                        new BigDecimal("100.00"),
                        new BigDecimal("500.00"),
                        new BigDecimal("50.00"),
                        new BigDecimal("550.00"),
                        false)));
        when(finishedGoodsService.getDispatchPreview(5L)).thenReturn(preview);

        PackagingSlipDto slip = new PackagingSlipDto(
                5L,
                UUID.randomUUID(),
                7L,
                "SO-7",
                "Dealer",
                "PS-5",
                "DISPATCHED",
                Instant.now(),
                Instant.now(),
                "factory.user",
                Instant.now(),
                "notes",
                111L,
                222L,
                List.of(),
                "FastMove Logistics",
                "Ayaan",
                "MH12AB1234",
                "LR-7788",
                "DC-PS-5",
                "/api/v1/dispatch/slip/5/challan/pdf"
        );
        when(finishedGoodsService.getPackagingSlip(5L)).thenReturn(slip);

        DispatchPreviewDto redactedPreview = controller.getDispatchPreview(5L).getBody().data();
        PackagingSlipDto redactedSlip = controller.getPackagingSlip(5L).getBody().data();

        assertThat(redactedPreview.totalOrderedAmount()).isNull();
        assertThat(redactedPreview.gstBreakdown()).isNull();
        assertThat(redactedPreview.lines().getFirst().unitPrice()).isNull();
        assertThat(redactedPreview.lines().getFirst().lineTotal()).isNull();
        assertThat(redactedSlip.journalEntryId()).isNull();
        assertThat(redactedSlip.cogsJournalEntryId()).isNull();
        assertThat(redactedSlip.deliveryChallanPdfPath()).isEqualTo("/api/v1/dispatch/slip/5/challan/pdf");

        SecurityContextHolder.clearContext();
    }

    private void setFactoryAuthentication() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(
                        "factory.user",
                        "pw",
                        "ROLE_FACTORY",
                        "dispatch.confirm"));
    }
}

