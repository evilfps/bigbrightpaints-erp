package com.bigbrightpaints.erp.modules.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import com.bigbrightpaints.erp.modules.inventory.dto.DispatchPreviewDto;
import com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipDto;
import com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipLineDto;
import com.bigbrightpaints.erp.modules.inventory.service.DeliveryChallanPdfService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class DispatchControllerTest {

  @Mock private FinishedGoodsService finishedGoodsService;
  @Mock private DeliveryChallanPdfService deliveryChallanPdfService;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void dispatchNamespace_hasNoPublicWriteMappings() {
    List<String> writeMappedMethods =
        Arrays.stream(DispatchController.class.getDeclaredMethods())
            .filter(this::hasWriteMapping)
            .map(Method::getName)
            .sorted()
            .toList();

    assertThat(writeMappedMethods).isEmpty();
    assertThat(Arrays.stream(DispatchController.class.getDeclaredMethods()).map(Method::getName))
        .doesNotContain("confirmDispatch", "cancelBackorder", "updateSlipStatus");
  }

  @Test
  void getPendingSlips_filtersDispatchedAndRedactsFactoryView() {
    DispatchController controller = new DispatchController(finishedGoodsService, deliveryChallanPdfService);
    setAuthentication("ROLE_FACTORY");

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
        packagingSlip(
            6L,
            "PS-6",
            "DISPATCHED",
            333L,
            444L,
            List.of(),
            null,
            null,
            null,
            null);
    when(finishedGoodsService.listPackagingSlips()).thenReturn(List.of(pending, dispatched));

    List<PackagingSlipDto> slips = controller.getPendingSlips().getBody().data();

    assertThat(slips).hasSize(1);
    assertThat(slips.getFirst().id()).isEqualTo(5L);
    assertThat(slips.getFirst().journalEntryId()).isNull();
    assertThat(slips.getFirst().cogsJournalEntryId()).isNull();
  }

  @Test
  void factoryViews_areRedactedForPreviewAndSlipDetails() {
    DispatchController controller = new DispatchController(finishedGoodsService, deliveryChallanPdfService);
    setAuthentication("ROLE_FACTORY");

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
    assertThat(redactedPreview.totalAvailableAmount()).isEqualByComparingTo("10.00");
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
    DispatchController controller = new DispatchController(finishedGoodsService, deliveryChallanPdfService);
    setAuthentication("ROLE_FACTORY");

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
    DispatchController controller = new DispatchController(finishedGoodsService, deliveryChallanPdfService);
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
    DispatchController controller = new DispatchController(finishedGoodsService, deliveryChallanPdfService);
    setAuthentication("ROLE_SALES");

    PackagingSlipDto slip =
        packagingSlip(
            9L,
            "PS-9",
            "READY",
            911L,
            922L,
            List.of(),
            null,
            "Driver",
            "MH12AB1234",
            "LR-900");
    when(finishedGoodsService.getPackagingSlipByOrder(70L)).thenReturn(slip);

    PackagingSlipDto response = controller.getPackagingSlipByOrder(70L).getBody().data();

    assertThat(response.journalEntryId()).isEqualTo(911L);
    assertThat(response.cogsJournalEntryId()).isEqualTo(922L);
    assertThat(response.driverName()).isEqualTo("Driver");
  }

  @Test
  void getPackagingSlip_returnsNullWhenSlipIsMissingInFactoryView() {
    DispatchController controller = new DispatchController(finishedGoodsService, deliveryChallanPdfService);
    setAuthentication("ROLE_FACTORY");
    when(finishedGoodsService.getPackagingSlip(404L)).thenReturn(null);

    assertThat(controller.getPackagingSlip(404L).getBody().data()).isNull();
  }

  @Test
  void getDispatchPreview_returnsNullWhenPreviewIsMissing() {
    DispatchController controller = new DispatchController(finishedGoodsService, deliveryChallanPdfService);
    setAuthentication("ROLE_FACTORY");
    when(finishedGoodsService.getDispatchPreview(404L)).thenReturn(null);

    assertThat(controller.getDispatchPreview(404L).getBody().data()).isNull();
  }

  @Test
  void accountingViewsAreNotOperationalFactoryViews() {
    DispatchController controller = new DispatchController(finishedGoodsService, deliveryChallanPdfService);
    setAuthentication("ROLE_FACTORY", "ROLE_ACCOUNTING");

    PackagingSlipDto slip =
        packagingSlip(
            13L,
            "PS-13",
            "DISPATCHED",
            131L,
            232L,
            List.of(),
            null,
            null,
            null,
            null);
    when(finishedGoodsService.getPackagingSlip(13L)).thenReturn(slip);

    PackagingSlipDto response = controller.getPackagingSlip(13L).getBody().data();

    assertThat(response.journalEntryId()).isEqualTo(131L);
    assertThat(response.cogsJournalEntryId()).isEqualTo(232L);
  }

  @Test
  void downloadDeliveryChallan_returnsInlinePdfResponse() {
    DispatchController controller = new DispatchController(finishedGoodsService, deliveryChallanPdfService);
    byte[] content = new byte[] {1, 2, 3};
    when(deliveryChallanPdfService.renderDeliveryChallanPdf(99L))
        .thenReturn(new DeliveryChallanPdfService.PdfDocument("delivery-challan-99.pdf", content));

    ResponseEntity<byte[]> response = controller.downloadDeliveryChallan(99L);

    assertThat(response.getHeaders().getFirst("Content-Disposition"))
        .isEqualTo("inline; filename=\"delivery-challan-99.pdf\"");
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    assertThat(response.getBody()).isEqualTo(content);
  }

  private boolean hasWriteMapping(Method method) {
    return method.isAnnotationPresent(PostMapping.class)
        || method.isAnnotationPresent(PatchMapping.class)
        || method.isAnnotationPresent(PutMapping.class)
        || method.isAnnotationPresent(DeleteMapping.class);
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
