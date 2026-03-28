package com.bigbrightpaints.erp.modules.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationResponse;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchPreviewDto;
import com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipDto;
import com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipLineDto;
import com.bigbrightpaints.erp.modules.inventory.service.DeliveryChallanPdfService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import com.bigbrightpaints.erp.modules.sales.service.SalesDispatchReconciliationService;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class DispatchControllerTest {

  @Mock private FinishedGoodsService finishedGoodsService;
  @Mock private SalesDispatchReconciliationService salesDispatchReconciliationService;
  @Mock private DeliveryChallanPdfService deliveryChallanPdfService;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void confirmDispatch_callsSalesOnce_andDoesNotDoubleDispatchInventory() {
    DispatchController controller =
        new DispatchController(
            finishedGoodsService, salesDispatchReconciliationService, deliveryChallanPdfService);
    Principal principal = () -> "factory.user";
    setAuthentication("ROLE_FACTORY", "dispatch.confirm");

    DispatchConfirmationRequest request =
        new DispatchConfirmationRequest(
            10L,
            List.of(
                new DispatchConfirmationRequest.LineConfirmation(
                    100L, new BigDecimal("2.50"), "Ship as-is")),
            "Dispatch notes",
            null,
            999L,
            "FastMove Logistics",
            "Ayaan",
            "MH12AB1234",
            "LR-7788");

    DispatchConfirmationResponse expected =
        new DispatchConfirmationResponse(
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
            "/api/v1/dispatch/slip/10/challan/pdf");
    when(finishedGoodsService.getDispatchConfirmation(10L)).thenReturn(expected);

    ResponseEntity<?> response = controller.confirmDispatch(request, principal);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    ArgumentCaptor<DispatchConfirmRequest> dispatchCaptor =
        ArgumentCaptor.forClass(DispatchConfirmRequest.class);
    verify(salesDispatchReconciliationService).confirmDispatch(dispatchCaptor.capture());

    DispatchConfirmRequest dispatchRequest = dispatchCaptor.getValue();
    assertThat(dispatchRequest.packingSlipId()).isEqualTo(10L);
    assertThat(dispatchRequest.confirmedBy()).isEqualTo("factory.user");
    assertThat(dispatchRequest.overrideRequestId()).isEqualTo(999L);
    assertThat(dispatchRequest.transporterName()).isEqualTo("FastMove Logistics");
    assertThat(dispatchRequest.driverName()).isEqualTo("Ayaan");
    assertThat(dispatchRequest.vehicleNumber()).isEqualTo("MH12AB1234");
    assertThat(dispatchRequest.challanReference()).isEqualTo("LR-7788");
    assertThat(dispatchRequest.lines()).hasSize(1);
    assertThat(dispatchRequest.lines().getFirst().lineId()).isEqualTo(100L);
    assertThat(dispatchRequest.lines().getFirst().shipQty()).isEqualByComparingTo("2.50");
    assertThat(dispatchRequest.lines().getFirst().notes()).isEqualTo("Ship as-is");

    DispatchConfirmationResponse redacted =
        (DispatchConfirmationResponse)
            ((com.bigbrightpaints.erp.shared.dto.ApiResponse<?>) response.getBody()).data();
    assertThat(redacted.journalEntryId()).isNull();
    assertThat(redacted.cogsJournalEntryId()).isNull();
    assertThat(redacted.totalShippedAmount()).isNull();
    assertThat(redacted.deliveryChallanPdfPath()).isEqualTo("/api/v1/dispatch/slip/10/challan/pdf");

    verify(finishedGoodsService).getPackagingSlip(10L);
    verify(finishedGoodsService).getDispatchConfirmation(10L);
    verifyNoMoreInteractions(salesDispatchReconciliationService, finishedGoodsService);
  }

  @Test
  void confirmDispatch_allowsDispatchedSlipReplayBeforeMetadataEnforcement() {
    DispatchController controller =
        new DispatchController(
            finishedGoodsService, salesDispatchReconciliationService, deliveryChallanPdfService);
    setAuthentication("ROLE_FACTORY", "dispatch.confirm");

    DispatchConfirmationRequest request =
        new DispatchConfirmationRequest(
            10L,
            List.of(
                new DispatchConfirmationRequest.LineConfirmation(
                    100L, new BigDecimal("2.50"), "Replay")),
            "Replay notes",
            null,
            null,
            null,
            null,
            null,
            null);
    when(finishedGoodsService.getPackagingSlip(10L))
        .thenReturn(
            packagingSlip(
                10L, "PS-10", "DISPATCHED", 111L, 222L, List.of(), null, null, null, null));
    when(finishedGoodsService.getDispatchConfirmation(10L))
        .thenReturn(
            new DispatchConfirmationResponse(
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
                null,
                null,
                null,
                "DC-PS-10",
                "/api/v1/dispatch/slip/10/challan/pdf"));

    assertThat(controller.confirmDispatch(request, () -> "factory.user").getStatusCode().value())
        .isEqualTo(200);
    verify(salesDispatchReconciliationService).confirmDispatch(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void confirmDispatch_requiresFactoryMetadataForCanonicalRoute() {
    DispatchController controller =
        new DispatchController(
            finishedGoodsService, salesDispatchReconciliationService, deliveryChallanPdfService);
    setAuthentication("ROLE_FACTORY", "dispatch.confirm");

    DispatchConfirmationRequest missingTransport =
        new DispatchConfirmationRequest(
            10L,
            List.of(new DispatchConfirmationRequest.LineConfirmation(100L, BigDecimal.ONE, null)),
            "Dispatch notes",
            null,
            null,
            null,
            null,
            "MH12AB1234",
            "LR-7788");

    DispatchConfirmationRequest missingVehicle =
        new DispatchConfirmationRequest(
            10L,
            List.of(new DispatchConfirmationRequest.LineConfirmation(100L, BigDecimal.ONE, null)),
            "Dispatch notes",
            null,
            null,
            "FastMove Logistics",
            null,
            null,
            "LR-7788");

    DispatchConfirmationRequest missingChallan =
        new DispatchConfirmationRequest(
            10L,
            List.of(new DispatchConfirmationRequest.LineConfirmation(100L, BigDecimal.ONE, null)),
            "Dispatch notes",
            null,
            null,
            "FastMove Logistics",
            null,
            "MH12AB1234",
            null);

    assertThatThrownBy(() -> controller.confirmDispatch(missingTransport, () -> "factory.user"))
        .hasMessageContaining(PortalRoleActionMatrix.transporterOrDriverRequiredMessage());
    assertThatThrownBy(() -> controller.confirmDispatch(missingVehicle, () -> "factory.user"))
        .hasMessageContaining(PortalRoleActionMatrix.vehicleNumberRequiredMessage());
    assertThatThrownBy(() -> controller.confirmDispatch(missingChallan, () -> "factory.user"))
        .hasMessageContaining(PortalRoleActionMatrix.challanReferenceRequiredMessage());
    verifyNoInteractions(salesDispatchReconciliationService);
  }

  @Test
  void confirmDispatch_usesSystemPrincipalAndDriverOnlyMetadata() {
    DispatchController controller =
        new DispatchController(
            finishedGoodsService, salesDispatchReconciliationService, deliveryChallanPdfService);
    setAuthentication("ROLE_FACTORY", "dispatch.confirm");

    DispatchConfirmationRequest request =
        new DispatchConfirmationRequest(
            11L,
            List.of(
                new DispatchConfirmationRequest.LineConfirmation(
                    111L, new BigDecimal("4.00"), "Ship full pallet")),
            "Dispatch notes",
            null,
            null,
            null,
            "Ayaan",
            "MH12AB4321",
            "LR-1111");
    when(finishedGoodsService.getPackagingSlip(11L))
        .thenReturn(
            packagingSlip(11L, "PS-11", "READY", 11L, 12L, List.of(), null, null, null, null));
    when(finishedGoodsService.getDispatchConfirmation(11L))
        .thenReturn(
            new DispatchConfirmationResponse(
                11L,
                "PS-11",
                "DISPATCHED",
                Instant.now(),
                "system",
                new BigDecimal("500.00"),
                new BigDecimal("400.00"),
                new BigDecimal("100.00"),
                11L,
                12L,
                List.of(
                    new DispatchConfirmationResponse.LineResult(
                        111L,
                        "FG-11",
                        "Primer",
                        new BigDecimal("5.00"),
                        new BigDecimal("4.00"),
                        new BigDecimal("1.00"),
                        new BigDecimal("125.00"),
                        new BigDecimal("500.00"),
                        "Ship full pallet")),
                null,
                null,
                "Ayaan",
                "MH12AB4321",
                "LR-1111",
                "DC-PS-11",
                "/api/v1/dispatch/slip/11/challan/pdf"));

    ResponseEntity<?> response = controller.confirmDispatch(request, null);

    ArgumentCaptor<DispatchConfirmRequest> dispatchCaptor =
        ArgumentCaptor.forClass(DispatchConfirmRequest.class);
    verify(salesDispatchReconciliationService).confirmDispatch(dispatchCaptor.capture());
    assertThat(dispatchCaptor.getValue().confirmedBy()).isEqualTo("system");
    assertThat(dispatchCaptor.getValue().transporterName()).isNull();
    assertThat(dispatchCaptor.getValue().driverName()).isEqualTo("Ayaan");

    DispatchConfirmationResponse redacted =
        (DispatchConfirmationResponse)
            ((com.bigbrightpaints.erp.shared.dto.ApiResponse<?>) response.getBody()).data();
    assertThat(redacted.lines()).hasSize(1);
    assertThat(redacted.lines().getFirst().unitCost()).isNull();
    assertThat(redacted.lines().getFirst().lineTotal()).isNull();
    assertThat(redacted.driverName()).isEqualTo("Ayaan");

    verify(finishedGoodsService).getPackagingSlip(11L);
    verify(finishedGoodsService).getDispatchConfirmation(11L);
    verifyNoMoreInteractions(salesDispatchReconciliationService, finishedGoodsService);
  }

  @Test
  void confirmDispatch_factoryViewNormalizesNullConfirmationLines() {
    DispatchController controller =
        new DispatchController(
            finishedGoodsService, salesDispatchReconciliationService, deliveryChallanPdfService);
    setAuthentication("ROLE_FACTORY", "dispatch.confirm");

    DispatchConfirmationRequest request =
        new DispatchConfirmationRequest(
            12L,
            List.of(new DispatchConfirmationRequest.LineConfirmation(120L, BigDecimal.ONE, null)),
            "Dispatch notes",
            null,
            null,
            "FastMove Logistics",
            null,
            "MH12AB9999",
            "LR-1212");
    when(finishedGoodsService.getDispatchConfirmation(12L))
        .thenReturn(
            new DispatchConfirmationResponse(
                12L,
                "PS-12",
                "DISPATCHED",
                Instant.now(),
                "factory.user",
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                121L,
                122L,
                null,
                null,
                "FastMove Logistics",
                null,
                "MH12AB9999",
                "LR-1212",
                "DC-PS-12",
                "/api/v1/dispatch/slip/12/challan/pdf"));

    DispatchConfirmationResponse response =
        controller.confirmDispatch(request, () -> "factory.user").getBody().data();

    assertThat(response.lines()).isEmpty();
    assertThat(response.totalShippedAmount()).isNull();
  }

  @Test
  void confirmDispatch_withoutOperationalFactoryAuthSkipsFactoryOnlyRedaction() {
    DispatchController controller =
        new DispatchController(
            finishedGoodsService, salesDispatchReconciliationService, deliveryChallanPdfService);
    SecurityContextHolder.clearContext();

    DispatchConfirmationRequest request =
        new DispatchConfirmationRequest(
            16L,
            List.of(new DispatchConfirmationRequest.LineConfirmation(160L, BigDecimal.ONE, null)),
            "Dispatch notes",
            null,
            null,
            "FastMove Logistics",
            null,
            "MH12AB1616",
            "LR-1616");
    when(finishedGoodsService.getDispatchConfirmation(16L))
        .thenReturn(
            new DispatchConfirmationResponse(
                16L,
                "PS-16",
                "DISPATCHED",
                Instant.now(),
                "factory.user",
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                161L,
                162L,
                List.of(
                    new DispatchConfirmationResponse.LineResult(
                        160L,
                        "FG-16",
                        "Primer",
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        new BigDecimal("100.00"),
                        new BigDecimal("100.00"),
                        "Ship")),
                null,
                "FastMove Logistics",
                null,
                "MH12AB1616",
                "LR-1616",
                "DC-PS-16",
                "/api/v1/dispatch/slip/16/challan/pdf"));

    DispatchConfirmationResponse response =
        controller.confirmDispatch(request, () -> "factory.user").getBody().data();

    assertThat(response.journalEntryId()).isEqualTo(161L);
    assertThat(response.totalShippedAmount()).isEqualByComparingTo("100.00");
    assertThat(response.lines().getFirst().unitCost()).isEqualByComparingTo("100.00");
  }

  @Test
  void getPendingSlips_filtersDispatchedAndRedactsFactoryView() {
    DispatchController controller =
        new DispatchController(
            finishedGoodsService, salesDispatchReconciliationService, deliveryChallanPdfService);
    setAuthentication("ROLE_FACTORY", "dispatch.confirm");

    PackagingSlipDto pending =
        packagingSlip(
            5L,
            "PS-5",
            "READY",
            111L,
            222L,
            List.of(),
            "FastMove Logistics",
            null,
            "MH12AB1234",
            "LR-7788");
    PackagingSlipDto dispatched =
        packagingSlip(6L, "PS-6", "DISPATCHED", 333L, 444L, List.of(), null, null, null, null);
    when(finishedGoodsService.listPackagingSlips()).thenReturn(List.of(pending, dispatched));

    List<PackagingSlipDto> slips = controller.getPendingSlips().getBody().data();

    assertThat(slips).hasSize(1);
    assertThat(slips.getFirst().id()).isEqualTo(5L);
    assertThat(slips.getFirst().journalEntryId()).isNull();
    assertThat(slips.getFirst().cogsJournalEntryId()).isNull();
  }

  @Test
  void factoryViews_areRedactedForPreviewAndSlipDetails() {
    DispatchController controller =
        new DispatchController(
            finishedGoodsService, salesDispatchReconciliationService, deliveryChallanPdfService);
    setAuthentication("ROLE_FACTORY", "dispatch.confirm");

    DispatchPreviewDto preview =
        new DispatchPreviewDto(
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
            List.of(
                new DispatchPreviewDto.LinePreview(
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

    PackagingSlipDto slip =
        packagingSlip(
            5L,
            "PS-5",
            "DISPATCHED",
            111L,
            222L,
            List.of(),
            "FastMove Logistics",
            "Ayaan",
            "MH12AB1234",
            "LR-7788");
    when(finishedGoodsService.getPackagingSlip(5L)).thenReturn(slip);

    DispatchPreviewDto redactedPreview = controller.getDispatchPreview(5L).getBody().data();
    PackagingSlipDto redactedSlip = controller.getPackagingSlip(5L).getBody().data();

    assertThat(redactedPreview.totalOrderedAmount()).isNull();
    assertThat(redactedPreview.totalAvailableAmount()).isNull();
    assertThat(redactedPreview.gstBreakdown()).isNull();
    assertThat(redactedPreview.lines().getFirst().unitPrice()).isNull();
    assertThat(redactedPreview.lines().getFirst().lineTotal()).isNull();
    assertThat(redactedSlip.journalEntryId()).isNull();
    assertThat(redactedSlip.cogsJournalEntryId()).isNull();
    assertThat(redactedSlip.deliveryChallanPdfPath())
        .isEqualTo("/api/v1/dispatch/slip/5/challan/pdf");
  }

  @Test
  void factorySlipView_redactsLineUnitCost() {
    DispatchController controller =
        new DispatchController(
            finishedGoodsService, salesDispatchReconciliationService, deliveryChallanPdfService);
    setAuthentication("ROLE_FACTORY", "dispatch.confirm");

    PackagingSlipDto slip =
        packagingSlip(
            15L,
            "PS-15",
            "READY",
            null,
            null,
            List.of(
                new PackagingSlipLineDto(
                    1L,
                    UUID.randomUUID(),
                    "BATCH-15",
                    "FG-15",
                    "Primer",
                    new BigDecimal("10.00"),
                    new BigDecimal("5.00"),
                    new BigDecimal("5.00"),
                    new BigDecimal("5.00"),
                    new BigDecimal("125.00"),
                    "line-notes")),
            "FastMove Logistics",
            "Ayaan",
            "MH12AB1234",
            "LR-1515");
    when(finishedGoodsService.getPackagingSlip(15L)).thenReturn(slip);

    PackagingSlipDto response = controller.getPackagingSlip(15L).getBody().data();

    assertThat(response.lines()).hasSize(1);
    assertThat(response.lines().getFirst().unitCost()).isNull();
    assertThat(response.lines().getFirst().productCode()).isEqualTo("FG-15");
  }

  @Test
  void salesViewsRetainFinancialFieldsWithoutFactoryRedaction() {
    DispatchController controller =
        new DispatchController(
            finishedGoodsService, salesDispatchReconciliationService, deliveryChallanPdfService);
    setAuthentication("ROLE_SALES");

    PackagingSlipDto slip =
        packagingSlip(
            5L,
            "PS-5",
            "DISPATCHED",
            111L,
            222L,
            List.of(),
            "FastMove Logistics",
            "Ayaan",
            "MH12AB1234",
            "LR-7788");
    when(finishedGoodsService.getPackagingSlip(5L)).thenReturn(slip);

    PackagingSlipDto response = controller.getPackagingSlip(5L).getBody().data();

    assertThat(response.journalEntryId()).isEqualTo(111L);
    assertThat(response.cogsJournalEntryId()).isEqualTo(222L);
    assertThat(response.deliveryChallanPdfPath()).isEqualTo("/api/v1/dispatch/slip/5/challan/pdf");
  }

  @Test
  void getPackagingSlipByOrder_salesViewsRetainFinancialFields() {
    DispatchController controller =
        new DispatchController(
            finishedGoodsService, salesDispatchReconciliationService, deliveryChallanPdfService);
    setAuthentication("ROLE_SALES");

    PackagingSlipDto slip =
        packagingSlip(
            9L, "PS-9", "READY", 911L, 922L, List.of(), null, "Driver", "MH12AB1234", "LR-900");
    when(finishedGoodsService.getPackagingSlipByOrder(70L)).thenReturn(slip);

    PackagingSlipDto response = controller.getPackagingSlipByOrder(70L).getBody().data();

    assertThat(response.journalEntryId()).isEqualTo(911L);
    assertThat(response.cogsJournalEntryId()).isEqualTo(922L);
    assertThat(response.driverName()).isEqualTo("Driver");
  }

  @Test
  void getPackagingSlip_returnsNullWhenSlipIsMissingInFactoryView() {
    DispatchController controller =
        new DispatchController(
            finishedGoodsService, salesDispatchReconciliationService, deliveryChallanPdfService);
    setAuthentication("ROLE_FACTORY", "dispatch.confirm");
    when(finishedGoodsService.getPackagingSlip(404L)).thenReturn(null);

    assertThat(controller.getPackagingSlip(404L).getBody().data()).isNull();
  }

  @Test
  void getDispatchPreview_returnsNullWhenPreviewIsMissing() {
    DispatchController controller =
        new DispatchController(
            finishedGoodsService, salesDispatchReconciliationService, deliveryChallanPdfService);
    setAuthentication("ROLE_FACTORY", "dispatch.confirm");
    when(finishedGoodsService.getDispatchPreview(404L)).thenReturn(null);

    assertThat(controller.getDispatchPreview(404L).getBody().data()).isNull();
  }

  @Test
  void getDispatchPreview_factoryViewNormalizesNullLines() {
    DispatchController controller =
        new DispatchController(
            finishedGoodsService, salesDispatchReconciliationService, deliveryChallanPdfService);
    setAuthentication("ROLE_FACTORY", "dispatch.confirm");

    when(finishedGoodsService.getDispatchPreview(14L))
        .thenReturn(
            new DispatchPreviewDto(
                14L,
                "PS-14",
                "READY",
                74L,
                "SO-74",
                "Dealer",
                "DLR-74",
                Instant.now(),
                new BigDecimal("300.00"),
                new BigDecimal("300.00"),
                new DispatchPreviewDto.GstBreakdown(
                    new BigDecimal("250.00"),
                    new BigDecimal("25.00"),
                    new BigDecimal("25.00"),
                    BigDecimal.ZERO,
                    new BigDecimal("50.00"),
                    new BigDecimal("300.00")),
                null));

    DispatchPreviewDto response = controller.getDispatchPreview(14L).getBody().data();

    assertThat(response.lines()).isEmpty();
    assertThat(response.totalOrderedAmount()).isNull();
    assertThat(response.totalAvailableAmount()).isNull();
    assertThat(response.gstBreakdown()).isNull();
  }

  @Test
  void accountingViewsAreNotOperationalFactoryViews() {
    DispatchController controller =
        new DispatchController(
            finishedGoodsService, salesDispatchReconciliationService, deliveryChallanPdfService);
    setAuthentication("ROLE_FACTORY", "ROLE_ACCOUNTING", "dispatch.confirm");

    PackagingSlipDto slip =
        packagingSlip(13L, "PS-13", "DISPATCHED", 131L, 232L, List.of(), null, null, null, null);
    when(finishedGoodsService.getPackagingSlip(13L)).thenReturn(slip);

    PackagingSlipDto response = controller.getPackagingSlip(13L).getBody().data();

    assertThat(response.journalEntryId()).isEqualTo(131L);
    assertThat(response.cogsJournalEntryId()).isEqualTo(232L);
  }

  @Test
  void getPackagingSlip_treatsNullAuthoritiesAsNonFactoryView() {
    DispatchController controller =
        new DispatchController(
            finishedGoodsService, salesDispatchReconciliationService, deliveryChallanPdfService);
    org.springframework.security.core.Authentication authentication =
        org.mockito.Mockito.mock(org.springframework.security.core.Authentication.class);
    when(authentication.getAuthorities()).thenReturn(null);
    SecurityContextHolder.getContext().setAuthentication(authentication);

    PackagingSlipDto slip =
        packagingSlip(31L, "PS-31", "READY", 311L, 322L, List.of(), null, null, null, null);
    when(finishedGoodsService.getPackagingSlip(31L)).thenReturn(slip);

    PackagingSlipDto response = controller.getPackagingSlip(31L).getBody().data();

    assertThat(response.journalEntryId()).isEqualTo(311L);
    assertThat(response.cogsJournalEntryId()).isEqualTo(322L);
  }

  @Test
  void cancelBackorder_usesSystemPrincipalWhenMissing() {
    DispatchController controller =
        new DispatchController(
            finishedGoodsService, salesDispatchReconciliationService, deliveryChallanPdfService);
    PackagingSlipDto slip =
        packagingSlip(55L, "PS-55", "CANCELED", 551L, 552L, List.of(), null, null, null, null);
    when(finishedGoodsService.cancelBackorderSlip(55L, "system", "Damaged labels"))
        .thenReturn(slip);

    var response = controller.cancelBackorder(55L, "Damaged labels", null).getBody();

    assertThat(response.message()).isEqualTo("Backorder canceled");
    assertThat(response.data().id()).isEqualTo(55L);
    verify(finishedGoodsService).cancelBackorderSlip(55L, "system", "Damaged labels");
  }

  @Test
  void updateSlipStatus_redactsFactoryViewAndNormalizesNullLines() {
    DispatchController controller =
        new DispatchController(
            finishedGoodsService, salesDispatchReconciliationService, deliveryChallanPdfService);
    setAuthentication("ROLE_FACTORY", "dispatch.confirm");

    PackagingSlipDto slip =
        packagingSlip(
            21L,
            "PS-21",
            "PACKING",
            211L,
            221L,
            null,
            "FastMove Logistics",
            null,
            "MH12AB2121",
            "LR-2121");
    when(finishedGoodsService.updateSlipStatus(21L, "PACKING")).thenReturn(slip);

    PackagingSlipDto response = controller.updateSlipStatus(21L, "PACKING").getBody().data();

    assertThat(response.lines()).isEmpty();
    assertThat(response.journalEntryId()).isNull();
    assertThat(response.cogsJournalEntryId()).isNull();
    assertThat(response.vehicleNumber()).isEqualTo("MH12AB2121");
  }

  @Test
  void downloadDeliveryChallan_returnsInlinePdfResponse() {
    DispatchController controller =
        new DispatchController(
            finishedGoodsService, salesDispatchReconciliationService, deliveryChallanPdfService);
    byte[] content = new byte[] {1, 2, 3};
    when(deliveryChallanPdfService.renderDeliveryChallanPdf(99L))
        .thenReturn(new DeliveryChallanPdfService.PdfDocument("delivery-challan-99.pdf", content));

    ResponseEntity<byte[]> response = controller.downloadDeliveryChallan(99L);

    assertThat(response.getHeaders().getFirst("Content-Disposition"))
        .isEqualTo("inline; filename=\"delivery-challan-99.pdf\"");
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    assertThat(response.getBody()).isEqualTo(content);
  }

  private PackagingSlipDto packagingSlip(
      Long id,
      String slipNumber,
      String status,
      Long journalEntryId,
      Long cogsJournalEntryId,
      List<PackagingSlipLineDto> lines,
      String transporterName,
      String driverName,
      String vehicleNumber,
      String challanReference) {
    return new PackagingSlipDto(
        id,
        UUID.randomUUID(),
        70L,
        "SO-70",
        "Dealer",
        slipNumber,
        status,
        Instant.now(),
        null,
        null,
        "DISPATCHED".equals(status) ? Instant.now() : null,
        null,
        journalEntryId,
        cogsJournalEntryId,
        lines,
        transporterName,
        driverName,
        vehicleNumber,
        challanReference,
        "DC-" + slipNumber,
        "/api/v1/dispatch/slip/" + id + "/challan/pdf");
  }

  private void setAuthentication(String... authorities) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("factory.user", "pw", authorities));
  }
}
