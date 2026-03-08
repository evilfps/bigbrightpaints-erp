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
import org.junit.jupiter.api.AfterEach;
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

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

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
    }

    @Test
    void confirmDispatch_requiresTransportActorForPureFactoryView() {
        DispatchController controller = new DispatchController(
                finishedGoodsService,
                salesDispatchReconciliationService,
                deliveryChallanPdfService);
        setAuthentication("ROLE_FACTORY");

        DispatchConfirmationRequest request = new DispatchConfirmationRequest(
                10L,
                List.of(new DispatchConfirmationRequest.LineConfirmation(100L, BigDecimal.ONE, null)),
                "Dispatch notes",
                null,
                null,
                null,
                null,
                "MH12AB1234",
                "LR-7788"
        );

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> controller.confirmDispatch(request, () -> "factory.user")).getMessage())
                .contains("transporterName or driverName");
    }

    @Test
    void confirmDispatch_requiresVehicleAndChallanReferenceForPureFactoryView() {
        DispatchController controller = new DispatchController(
                finishedGoodsService,
                salesDispatchReconciliationService,
                deliveryChallanPdfService);
        setAuthentication("ROLE_FACTORY");

        DispatchConfirmationRequest missingVehicle = new DispatchConfirmationRequest(
                10L,
                List.of(new DispatchConfirmationRequest.LineConfirmation(100L, BigDecimal.ONE, null)),
                "Dispatch notes",
                null,
                null,
                "FastMove Logistics",
                null,
                null,
                "LR-7788"
        );
        DispatchConfirmationRequest missingChallan = new DispatchConfirmationRequest(
                10L,
                List.of(new DispatchConfirmationRequest.LineConfirmation(100L, BigDecimal.ONE, null)),
                "Dispatch notes",
                null,
                null,
                "FastMove Logistics",
                null,
                "MH12AB1234",
                null
        );

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> controller.confirmDispatch(missingVehicle, () -> "factory.user")).getMessage())
                .contains("vehicleNumber");
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> controller.confirmDispatch(missingChallan, () -> "factory.user")).getMessage())
                .contains("challanReference");
    }

    @Test
    void elevatedViews_bypassFactoryValidationAndRetainFinancialFields() {
        DispatchController controller = new DispatchController(
                finishedGoodsService,
                salesDispatchReconciliationService,
                deliveryChallanPdfService);
        Principal principal = () -> "admin.user";
        setAuthentication("ROLE_FACTORY", "ROLE_ADMIN");

        DispatchConfirmationRequest request = new DispatchConfirmationRequest(
                10L,
                List.of(new DispatchConfirmationRequest.LineConfirmation(100L, BigDecimal.ONE, null)),
                "Dispatch notes",
                null,
                null,
                null,
                null,
                null,
                null
        );
        DispatchConfirmationResponse expected = new DispatchConfirmationResponse(
                10L,
                "PS-10",
                "DISPATCHED",
                Instant.now(),
                "admin.user",
                new BigDecimal("120.00"),
                new BigDecimal("120.00"),
                BigDecimal.ZERO,
                111L,
                222L,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                "DC-PS-10",
                "/api/v1/dispatch/slip/10/challan/pdf"
        );
        when(finishedGoodsService.getDispatchConfirmation(10L)).thenReturn(expected);

        DispatchConfirmationResponse response = controller.confirmDispatch(request, principal).getBody().data();

        assertThat(response.journalEntryId()).isEqualTo(111L);
        assertThat(response.cogsJournalEntryId()).isEqualTo(222L);
        verify(salesDispatchReconciliationService).confirmDispatch(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void salesViewsRetainFinancialFieldsWithoutFactoryRedaction() {
        DispatchController controller = new DispatchController(
                finishedGoodsService,
                salesDispatchReconciliationService,
                deliveryChallanPdfService);
        setAuthentication("ROLE_SALES");

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
                "sales.user",
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

        PackagingSlipDto response = controller.getPackagingSlip(5L).getBody().data();

        assertThat(response.journalEntryId()).isEqualTo(111L);
        assertThat(response.cogsJournalEntryId()).isEqualTo(222L);
        assertThat(response.deliveryChallanPdfPath()).isEqualTo("/api/v1/dispatch/slip/5/challan/pdf");
    }

    @Test
    void getPackagingSlipByOrder_salesViewsRetainFinancialFields() {
        DispatchController controller = new DispatchController(
                finishedGoodsService,
                salesDispatchReconciliationService,
                deliveryChallanPdfService);
        setAuthentication("ROLE_SALES");

        PackagingSlipDto slip = new PackagingSlipDto(
                9L,
                UUID.randomUUID(),
                70L,
                "SO-70",
                "Dealer",
                "PS-9",
                "READY",
                Instant.now(),
                null,
                null,
                null,
                null,
                911L,
                922L,
                List.of(),
                null,
                "Driver",
                "MH12AB1234",
                "LR-900",
                "DC-PS-9",
                "/api/v1/dispatch/slip/9/challan/pdf"
        );
        when(finishedGoodsService.getPackagingSlipByOrder(70L)).thenReturn(slip);

        PackagingSlipDto response = controller.getPackagingSlipByOrder(70L).getBody().data();

        assertThat(response.journalEntryId()).isEqualTo(911L);
        assertThat(response.cogsJournalEntryId()).isEqualTo(922L);
        assertThat(response.driverName()).isEqualTo("Driver");
    }

    @Test
    void updateSlipStatus_factoryViewRedactsFinancialFields() {
        DispatchController controller = new DispatchController(
                finishedGoodsService,
                salesDispatchReconciliationService,
                deliveryChallanPdfService);
        setAuthentication("ROLE_FACTORY");

        PackagingSlipDto slip = new PackagingSlipDto(
                12L,
                UUID.randomUUID(),
                72L,
                "SO-72",
                "Dealer",
                "PS-12",
                "PACKING",
                Instant.now(),
                null,
                null,
                null,
                null,
                101L,
                202L,
                List.of(),
                "FastMove",
                null,
                "MH12AB1234",
                "LR-1200",
                "DC-PS-12",
                "/api/v1/dispatch/slip/12/challan/pdf"
        );
        when(finishedGoodsService.updateSlipStatus(12L, "PACKING")).thenReturn(slip);

        PackagingSlipDto response = controller.updateSlipStatus(12L, "PACKING").getBody().data();

        assertThat(response.journalEntryId()).isNull();
        assertThat(response.cogsJournalEntryId()).isNull();
        assertThat(response.vehicleNumber()).isEqualTo("MH12AB1234");
    }

    @Test
    void factoryViewAllowsDriverOnlyTransportActor() {
        DispatchController controller = new DispatchController(
                finishedGoodsService,
                salesDispatchReconciliationService,
                deliveryChallanPdfService);
        setAuthentication("ROLE_FACTORY");

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
                null,
                "Ayaan",
                "MH12AB1234",
                "LR-7788",
                "DC-PS-10",
                "/api/v1/dispatch/slip/10/challan/pdf"
        );
        when(finishedGoodsService.getDispatchConfirmation(10L)).thenReturn(expected);

        DispatchConfirmationResponse response = controller.confirmDispatch(
                        new DispatchConfirmationRequest(
                                10L,
                                List.of(new DispatchConfirmationRequest.LineConfirmation(100L, BigDecimal.ONE, null)),
                                "Dispatch notes",
                                null,
                                null,
                                null,
                                "Ayaan",
                                "MH12AB1234",
                                "LR-7788"),
                        () -> "factory.user")
                .getBody().data();

        assertThat(response.driverName()).isEqualTo("Ayaan");
        verify(salesDispatchReconciliationService).confirmDispatch(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getPackagingSlip_returnsNullWhenSlipIsMissingInFactoryView() {
        DispatchController controller = new DispatchController(
                finishedGoodsService,
                salesDispatchReconciliationService,
                deliveryChallanPdfService);
        setAuthentication("ROLE_FACTORY");
        when(finishedGoodsService.getPackagingSlip(404L)).thenReturn(null);

        assertThat(controller.getPackagingSlip(404L).getBody().data()).isNull();
    }

    @Test
    void getDispatchPreview_returnsNullWhenPreviewIsMissing() {
        DispatchController controller = new DispatchController(
                finishedGoodsService,
                salesDispatchReconciliationService,
                deliveryChallanPdfService);
        setAuthentication("ROLE_FACTORY");
        when(finishedGoodsService.getDispatchPreview(404L)).thenReturn(null);

        assertThat(controller.getDispatchPreview(404L).getBody().data()).isNull();
    }

    @Test
    void confirmDispatch_returnsNullConfirmationWhenServiceReturnsNull() {
        DispatchController controller = new DispatchController(
                finishedGoodsService,
                salesDispatchReconciliationService,
                deliveryChallanPdfService);
        setAuthentication("ROLE_ADMIN");
        when(finishedGoodsService.getDispatchConfirmation(10L)).thenReturn(null);

        assertThat(controller.confirmDispatch(
                new DispatchConfirmationRequest(
                        10L,
                        List.of(new DispatchConfirmationRequest.LineConfirmation(100L, BigDecimal.ONE, null)),
                        "notes",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                () -> "admin.user").getBody().data()).isNull();
    }

    @Test
    void unauthenticatedViewsDoNotRedactSlipDetails() {
        DispatchController controller = new DispatchController(
                finishedGoodsService,
                salesDispatchReconciliationService,
                deliveryChallanPdfService);

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
                "sales.user",
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

        PackagingSlipDto response = controller.getPackagingSlip(5L).getBody().data();

        assertThat(response.journalEntryId()).isEqualTo(111L);
        assertThat(response.cogsJournalEntryId()).isEqualTo(222L);
    }

    @Test
    void accountingViewsAreNotOperationalFactoryViews() {
        DispatchController controller = new DispatchController(
                finishedGoodsService,
                salesDispatchReconciliationService,
                deliveryChallanPdfService);
        setAuthentication("ROLE_FACTORY", "ROLE_ACCOUNTING");

        PackagingSlipDto slip = new PackagingSlipDto(
                13L,
                UUID.randomUUID(),
                73L,
                "SO-73",
                "Dealer",
                "PS-13",
                "DISPATCHED",
                Instant.now(),
                null,
                null,
                null,
                null,
                131L,
                232L,
                List.of(),
                null,
                null,
                null,
                null,
                "DC-PS-13",
                "/api/v1/dispatch/slip/13/challan/pdf"
        );
        when(finishedGoodsService.getPackagingSlip(13L)).thenReturn(slip);

        PackagingSlipDto response = controller.getPackagingSlip(13L).getBody().data();

        assertThat(response.journalEntryId()).isEqualTo(131L);
        assertThat(response.cogsJournalEntryId()).isEqualTo(232L);
    }

    @Test
    void factoryViews_handleNullPreviewAndConfirmationLines() {
        DispatchController controller = new DispatchController(
                finishedGoodsService,
                salesDispatchReconciliationService,
                deliveryChallanPdfService);
        setAuthentication("ROLE_FACTORY");

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
                new BigDecimal("450.00"),
                null,
                null);
        when(finishedGoodsService.getDispatchPreview(5L)).thenReturn(preview);

        DispatchConfirmationResponse confirmation = new DispatchConfirmationResponse(
                5L,
                "PS-5",
                "DISPATCHED",
                Instant.now(),
                "factory.user",
                new BigDecimal("500.00"),
                new BigDecimal("450.00"),
                new BigDecimal("50.00"),
                111L,
                222L,
                null,
                77L,
                "FastMove Logistics",
                "Ayaan",
                "MH12AB1234",
                "LR-7788",
                "DC-PS-5",
                "/api/v1/dispatch/slip/5/challan/pdf"
        );
        when(finishedGoodsService.getDispatchConfirmation(5L)).thenReturn(confirmation);

        DispatchPreviewDto redactedPreview = controller.getDispatchPreview(5L).getBody().data();
        DispatchConfirmationResponse redactedConfirmation = controller.confirmDispatch(
                        new DispatchConfirmationRequest(
                                5L,
                                List.of(new DispatchConfirmationRequest.LineConfirmation(100L, BigDecimal.ONE, null)),
                                "notes",
                                null,
                                null,
                                "FastMove Logistics",
                                null,
                                "MH12AB1234",
                                "LR-7788"),
                        () -> "factory.user")
                .getBody().data();

        assertThat(redactedPreview.lines()).isEmpty();
        assertThat(redactedConfirmation.lines()).isEmpty();
        assertThat(redactedConfirmation.journalEntryId()).isNull();
        assertThat(redactedConfirmation.cogsJournalEntryId()).isNull();
    }

    @Test
    void getPendingSlips_filtersDispatchedAndRedactsFactoryView() {
        DispatchController controller = new DispatchController(
                finishedGoodsService,
                salesDispatchReconciliationService,
                deliveryChallanPdfService);
        setAuthentication("ROLE_FACTORY");

        PackagingSlipDto pending = new PackagingSlipDto(
                5L,
                UUID.randomUUID(),
                7L,
                "SO-7",
                "Dealer",
                "PS-5",
                "READY",
                Instant.now(),
                null,
                null,
                null,
                null,
                111L,
                222L,
                List.of(),
                "FastMove Logistics",
                null,
                "MH12AB1234",
                "LR-7788",
                "DC-PS-5",
                "/api/v1/dispatch/slip/5/challan/pdf"
        );
        PackagingSlipDto dispatched = new PackagingSlipDto(
                6L,
                UUID.randomUUID(),
                8L,
                "SO-8",
                "Dealer",
                "PS-6",
                "DISPATCHED",
                Instant.now(),
                null,
                null,
                null,
                null,
                333L,
                444L,
                List.of(),
                null,
                null,
                null,
                null,
                "DC-PS-6",
                "/api/v1/dispatch/slip/6/challan/pdf"
        );
        when(finishedGoodsService.listPackagingSlips()).thenReturn(List.of(pending, dispatched));

        List<PackagingSlipDto> slips = controller.getPendingSlips().getBody().data();

        assertThat(slips).hasSize(1);
        assertThat(slips.getFirst().id()).isEqualTo(5L);
        assertThat(slips.getFirst().journalEntryId()).isNull();
        assertThat(slips.getFirst().cogsJournalEntryId()).isNull();
    }

    @Test
    void downloadDeliveryChallan_returnsInlinePdfResponse() {
        DispatchController controller = new DispatchController(
                finishedGoodsService,
                salesDispatchReconciliationService,
                deliveryChallanPdfService);
        byte[] content = new byte[] {1, 2, 3};
        when(deliveryChallanPdfService.renderDeliveryChallanPdf(99L))
                .thenReturn(new DeliveryChallanPdfService.PdfDocument("delivery-challan-99.pdf", content));

        ResponseEntity<byte[]> response = controller.downloadDeliveryChallan(99L);

        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .isEqualTo("inline; filename=\"delivery-challan-99.pdf\"");
        assertThat(response.getBody()).isEqualTo(content);
    }

    private void setFactoryAuthentication() {
        setAuthentication("ROLE_FACTORY", "dispatch.confirm");
    }

    private void setAuthentication(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(
                        "factory.user",
                        "pw",
                        authorities));
    }
}

