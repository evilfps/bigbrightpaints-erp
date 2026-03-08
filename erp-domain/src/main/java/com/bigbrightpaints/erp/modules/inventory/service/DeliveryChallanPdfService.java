package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipLine;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

@Service
public class DeliveryChallanPdfService {

    private final CompanyContextService companyContextService;
    private final PackagingSlipRepository packagingSlipRepository;
    private final TemplateEngine templateEngine;

    public DeliveryChallanPdfService(CompanyContextService companyContextService,
                                     PackagingSlipRepository packagingSlipRepository,
                                     TemplateEngine templateEngine) {
        this.companyContextService = companyContextService;
        this.packagingSlipRepository = packagingSlipRepository;
        this.templateEngine = templateEngine;
    }

    public PdfDocument renderDeliveryChallanPdf(Long packagingSlipId) {
        Company company = companyContextService.requireCurrentCompany();
        PackagingSlip slip = packagingSlipRepository.findByIdAndCompany(packagingSlipId, company)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Packaging slip not found"));
        if (slip.getConfirmedAt() == null) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidState("Delivery challan is available only after dispatch confirmation");
        }
        if (!isEligibleForDeliveryChallan(slip)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidState("Delivery challan is available only for dispatched slips with shipped quantity");
        }

        SalesOrder order = slip.getSalesOrder();
        Dealer dealer = order != null ? order.getDealer() : null;
        String timezone = company.getTimezone() != null ? company.getTimezone() : "UTC";
        LocalDate dispatchDate = slip.getDispatchedAt() != null
                ? LocalDate.ofInstant(slip.getDispatchedAt(), ZoneId.of(timezone))
                : LocalDate.ofInstant(slip.getConfirmedAt(), ZoneId.of(timezone));

        List<DeliveryChallanLineView> lines = slip.getLines().stream()
                .map(this::toLineView)
                .filter(Objects::nonNull)
                .toList();

        DeliveryChallanView view = new DeliveryChallanView(
                sanitize(company.getName()),
                sanitize(company.getCode()),
                sanitize(DispatchArtifactPaths.deliveryChallanNumber(slip.getSlipNumber())),
                sanitize(slip.getSlipNumber()),
                sanitize(order != null ? order.getOrderNumber() : null),
                dispatchDate,
                sanitize(dealer != null ? dealer.getName() : null),
                sanitize(dealer != null ? dealer.getAddress() : null),
                sanitize(dealer != null ? dealer.getPhone() : null),
                sanitize(slip.getTransporterName()),
                sanitize(slip.getDriverName()),
                sanitize(slip.getVehicleNumber()),
                sanitize(slip.getChallanReference()),
                lines
        );

        Context context = new Context();
        context.setVariable("challan", view);
        String html = templateEngine.process("delivery-challan-template", context);
        byte[] pdf = renderPdf(html);
        String fileName = "delivery-challan-"
                + (slip.getSlipNumber() != null ? slip.getSlipNumber() : slip.getId())
                + ".pdf";
        return new PdfDocument(fileName, pdf);
    }

    private DeliveryChallanLineView toLineView(PackagingSlipLine line) {
        if (line == null || line.getFinishedGoodBatch() == null || line.getFinishedGoodBatch().getFinishedGood() == null) {
            return null;
        }
        BigDecimal shipped = line.getShippedQuantity();
        if (shipped == null) {
            shipped = line.getQuantity();
        }
        return new DeliveryChallanLineView(
                sanitize(line.getFinishedGoodBatch().getFinishedGood().getProductCode()),
                sanitize(line.getFinishedGoodBatch().getFinishedGood().getName()),
                sanitize(line.getFinishedGoodBatch().getBatchCode()),
                shipped != null ? shipped : BigDecimal.ZERO,
                sanitize(line.getNotes())
        );
    }

    private byte[] renderPdf(String html) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, "");
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception ex) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidState("Failed to render delivery challan PDF", ex);
        }
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value;
    }

    private boolean isEligibleForDeliveryChallan(PackagingSlip slip) {
        if (slip == null || !"DISPATCHED".equalsIgnoreCase(slip.getStatus())) {
            return false;
        }
        return slip.getLines().stream()
                .map(line -> {
                    BigDecimal shipped = line.getShippedQuantity();
                    return shipped != null ? shipped : line.getQuantity();
                })
                .filter(Objects::nonNull)
                .anyMatch(quantity -> quantity.compareTo(BigDecimal.ZERO) > 0);
    }

    public record DeliveryChallanLineView(String productCode,
                                          String productName,
                                          String batchCode,
                                          BigDecimal shippedQuantity,
                                          String notes) {
    }

    public record DeliveryChallanView(String companyName,
                                      String companyCode,
                                      String challanNumber,
                                      String slipNumber,
                                      String orderNumber,
                                      LocalDate dispatchDate,
                                      String dealerName,
                                      String deliveryAddress,
                                      String dealerPhone,
                                      String transporterName,
                                      String driverName,
                                      String vehicleNumber,
                                      String challanReference,
                                      List<DeliveryChallanLineView> lines) {
    }

    public record PdfDocument(String fileName, byte[] content) {
        public PdfDocument {
            Objects.requireNonNull(content, "PDF content is required");
        }
    }
}
