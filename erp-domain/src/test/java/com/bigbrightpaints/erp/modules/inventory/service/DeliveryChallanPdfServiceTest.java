package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipLine;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class DeliveryChallanPdfServiceTest {

    @Mock
    private CompanyContextService companyContextService;

    @Mock
    private PackagingSlipRepository packagingSlipRepository;

    @Mock
    private TemplateEngine templateEngine;

    private DeliveryChallanPdfService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryChallanPdfService(companyContextService, packagingSlipRepository, templateEngine);
    }

    @Test
    void renderDeliveryChallanPdf_requiresDispatchedSlip() {
        Company company = company("Acme & Co", "ACME");
        PackagingSlip slip = dispatchedSlip(company, false);
        slip.setStatus("PENDING_STOCK");

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(packagingSlipRepository.findByIdAndCompany(15L, company)).thenReturn(Optional.of(slip));

        assertThatThrownBy(() -> service.renderDeliveryChallanPdf(15L))
                .hasMessageContaining("available only for dispatched slips");

        verifyNoInteractions(templateEngine);
    }

    @Test
    void renderDeliveryChallanPdf_requiresPositiveShippedQuantity() {
        Company company = company("Acme & Co", "ACME");
        PackagingSlip slip = dispatchedSlip(company, false);

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(packagingSlipRepository.findByIdAndCompany(16L, company)).thenReturn(Optional.of(slip));

        assertThatThrownBy(() -> service.renderDeliveryChallanPdf(16L))
                .hasMessageContaining("available only for dispatched slips with shipped quantity");

        verifyNoInteractions(templateEngine);
    }

    @Test
    void renderDeliveryChallanPdf_requiresConfirmationTimestamp() {
        Company company = company("Acme & Co", "ACME");
        PackagingSlip slip = dispatchedSlip(company, true);
        slip.setConfirmedAt(null);

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(packagingSlipRepository.findByIdAndCompany(17L, company)).thenReturn(Optional.of(slip));

        assertThatThrownBy(() -> service.renderDeliveryChallanPdf(17L))
                .hasMessageContaining("available only after dispatch confirmation");

        verifyNoInteractions(templateEngine);
    }

    @Test
    void renderDeliveryChallanPdf_rejectsMissingPackagingSlip() {
        Company company = company("Acme & Co", "ACME");

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(packagingSlipRepository.findByIdAndCompany(99L, company)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.renderDeliveryChallanPdf(99L))
                .hasMessageContaining("Packaging slip not found");
    }

    @Test
    void renderDeliveryChallanPdf_rendersPdfWithDispatchMetadataAndFallbackDate() {
        Company company = company("Acme & Co", "ACME");
        PackagingSlip slip = dispatchedSlip(company, true);
        slip.setDispatchedAt(null);
        slip.setTransporterName("Rapid Movers");
        slip.setDriverName("Ayaan");
        slip.setVehicleNumber("MH12AB1234");
        slip.setChallanReference("LR-7788");

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(packagingSlipRepository.findByIdAndCompany(18L, company)).thenReturn(Optional.of(slip));
        SpringTemplateEngine stubTemplateEngine = new SpringTemplateEngine();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        stubTemplateEngine.setTemplateResolver(resolver);

        DeliveryChallanPdfService realService =
                new DeliveryChallanPdfService(companyContextService, packagingSlipRepository, stubTemplateEngine);

        DeliveryChallanPdfService.PdfDocument pdf = realService.renderDeliveryChallanPdf(18L);

        assertThat(pdf.fileName()).isEqualTo("delivery-challan-PS-17.pdf");
        assertThat(pdf.content()).isNotEmpty();
    }

    @Test
    void renderDeliveryChallanPdf_fallsBackToSlipIdAndBlankOrderDealerFields() {
        Company company = company("Acme & Co", "ACME");
        company.setTimezone(null);
        PackagingSlip slip = dispatchedSlip(company, true);
        slip.setSlipNumber(null);
        slip.setSalesOrder(null);

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(packagingSlipRepository.findByIdAndCompany(19L, company)).thenReturn(Optional.of(slip));
        SpringTemplateEngine stubTemplateEngine = new SpringTemplateEngine();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        stubTemplateEngine.setTemplateResolver(resolver);

        DeliveryChallanPdfService realService =
                new DeliveryChallanPdfService(companyContextService, packagingSlipRepository, stubTemplateEngine);

        DeliveryChallanPdfService.PdfDocument pdf = realService.renderDeliveryChallanPdf(19L);

        assertThat(pdf.fileName()).isEqualTo("delivery-challan-null.pdf");
        assertThat(pdf.content()).isNotEmpty();
    }

    @Test
    void renderDeliveryChallanPdf_unescapesStoredEntitiesBeforeTemplateEscaping() throws Exception {
        Company company = company("Acme &amp; Co", "AC&amp;ME");
        PackagingSlip slip = dispatchedSlip(company, true);
        slip.getSalesOrder().getDealer().setName("Dealer &amp; Sons");
        slip.setTransporterName("Rapid &amp; Safe Logistics");

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(packagingSlipRepository.findByIdAndCompany(20L, company)).thenReturn(Optional.of(slip));

        DeliveryChallanPdfService realService = new DeliveryChallanPdfService(
                companyContextService,
                packagingSlipRepository,
                templateEngine());

        DeliveryChallanPdfService.PdfDocument pdf = realService.renderDeliveryChallanPdf(20L);
        String text = extractText(pdf.content());

        assertThat(text).contains("Acme & Co");
        assertThat(text).contains("Dealer & Sons");
        assertThat(text).contains("Rapid & Safe Logistics");
        assertThat(text).doesNotContain("&amp;");
    }

    @Test
    void toLineView_preservesRawTextAndUsesShippedQuantity() throws Exception {
        PackagingSlipLine line = dispatchedSlip(company("Acme & Sons", "AC&ME"), true).getLines().getFirst();
        line.setNotes("Use <care> & verify");

        Method toLineView = DeliveryChallanPdfService.class.getDeclaredMethod("toLineView", PackagingSlipLine.class);
        toLineView.setAccessible(true);

        DeliveryChallanPdfService.DeliveryChallanLineView view =
                (DeliveryChallanPdfService.DeliveryChallanLineView) toLineView.invoke(service, line);

        assertThat(view.productCode()).isEqualTo("FG-17");
        assertThat(view.productName()).isEqualTo("Primer & Coat");
        assertThat(view.batchCode()).isEqualTo("BATCH-17");
        assertThat(view.shippedQuantity()).isEqualByComparingTo("5.00");
        assertThat(view.notes()).isEqualTo("Use <care> & verify");
    }

    @Test
    void renderPdf_returnsBytesForMinimalHtml() throws Exception {
        Method renderPdf = DeliveryChallanPdfService.class.getDeclaredMethod("renderPdf", String.class);
        renderPdf.setAccessible(true);

        byte[] pdf = (byte[]) renderPdf.invoke(service, "<html><body>challan</body></html>");

        assertThat(pdf).isNotEmpty();
    }

    @Test
    void normalizeTemplateText_collapsesBlankValues_andUnescapesHtmlEntities() throws Exception {
        Method normalizeTemplateText = DeliveryChallanPdfService.class
                .getDeclaredMethod("normalizeTemplateText", String.class);
        normalizeTemplateText.setAccessible(true);

        assertThat(normalizeTemplateText.invoke(service, "A&B <tag> \"quoted\""))
                .isEqualTo("A&B <tag> \"quoted\"");
        assertThat(normalizeTemplateText.invoke(service, "Acme &amp; Co")).isEqualTo("Acme & Co");
        assertThat(normalizeTemplateText.invoke(service, (Object) null)).isEqualTo("");
        assertThat(normalizeTemplateText.invoke(service, "   ")).isEqualTo("");
    }

    @Test
    void toLineView_fallsBackToReservedQuantityWhenShippedQuantityIsMissing() throws Exception {
        PackagingSlipLine line = dispatchedSlip(company("Acme & Sons", "ACME"), true).getLines().getFirst();
        line.setShippedQuantity(null);
        line.setQuantity(new BigDecimal("4.50"));

        Method toLineView = DeliveryChallanPdfService.class.getDeclaredMethod("toLineView", PackagingSlipLine.class);
        toLineView.setAccessible(true);

        DeliveryChallanPdfService.DeliveryChallanLineView view =
                (DeliveryChallanPdfService.DeliveryChallanLineView) toLineView.invoke(service, line);

        assertThat(view.shippedQuantity()).isEqualByComparingTo("4.50");
    }

    @Test
    void toLineView_returnsNullForIncompleteLine() throws Exception {
        PackagingSlipLine line = new PackagingSlipLine();

        Method toLineView = DeliveryChallanPdfService.class.getDeclaredMethod("toLineView", PackagingSlipLine.class);
        toLineView.setAccessible(true);

        assertThat(toLineView.invoke(service, line)).isNull();
        assertThat(toLineView.invoke(service, new Object[] {null})).isNull();
    }

    @Test
    void toLineView_defaultsMissingQuantitiesToZero() throws Exception {
        PackagingSlipLine line = dispatchedSlip(company("Acme & Sons", "ACME"), true).getLines().getFirst();
        line.setShippedQuantity(null);
        line.setQuantity(null);

        Method toLineView = DeliveryChallanPdfService.class.getDeclaredMethod("toLineView", PackagingSlipLine.class);
        toLineView.setAccessible(true);

        DeliveryChallanPdfService.DeliveryChallanLineView view =
                (DeliveryChallanPdfService.DeliveryChallanLineView) toLineView.invoke(service, line);

        assertThat(view.shippedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void renderPdf_wrapsRendererFailures() throws Exception {
        Method renderPdf = DeliveryChallanPdfService.class.getDeclaredMethod("renderPdf", String.class);
        renderPdf.setAccessible(true);

        assertThatThrownBy(() -> renderPdf.invoke(service, new Object[] {null}))
                .hasCauseInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
                .satisfies(ex -> assertThat(ex.getCause().getMessage())
                        .contains("Failed to render delivery challan PDF"));
    }

    @Test
    void isEligibleForDeliveryChallan_handlesNullStatusAndFallbackQuantities() throws Exception {
        Method isEligible = DeliveryChallanPdfService.class
                .getDeclaredMethod("isEligibleForDeliveryChallan", PackagingSlip.class);
        isEligible.setAccessible(true);

        PackagingSlip pendingSlip = dispatchedSlip(company("Acme", "ACME"), true);
        pendingSlip.setStatus(null);
        PackagingSlip fallbackSlip = dispatchedSlip(company("Acme", "ACME"), true);
        fallbackSlip.getLines().getFirst().setShippedQuantity(null);

        assertThat(isEligible.invoke(service, new Object[] {null})).isEqualTo(false);
        assertThat(isEligible.invoke(service, pendingSlip)).isEqualTo(false);
        assertThat(isEligible.invoke(service, fallbackSlip)).isEqualTo(true);
    }

    private Company company(String name, String code) {
        Company company = new Company();
        company.setName(name);
        company.setCode(code);
        company.setTimezone("UTC");
        return company;
    }

    private PackagingSlip dispatchedSlip(Company company, boolean shipped) {
        Dealer dealer = new Dealer();
        dealer.setName("Dealer");
        dealer.setAddress("Address");
        dealer.setPhone("1234567890");

        SalesOrder order = new SalesOrder();
        order.setDealer(dealer);
        order.setOrderNumber("SO-17");

        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setProductCode("FG-17");
        finishedGood.setName("Primer & Coat");

        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode("BATCH-17");

        PackagingSlipLine line = new PackagingSlipLine();
        line.setFinishedGoodBatch(batch);
        line.setOrderedQuantity(new BigDecimal("5.00"));
        line.setQuantity(new BigDecimal("5.00"));
        line.setShippedQuantity(shipped ? new BigDecimal("5.00") : BigDecimal.ZERO);

        PackagingSlip slip = new PackagingSlip();
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-17");
        slip.setStatus("DISPATCHED");
        slip.setConfirmedAt(Instant.parse("2026-03-08T10:15:30Z"));
        slip.setDispatchedAt(Instant.parse("2026-03-08T10:15:30Z"));
        line.setPackagingSlip(slip);
        slip.getLines().add(line);
        return slip;
    }

    private SpringTemplateEngine templateEngine() {
        SpringTemplateEngine stubTemplateEngine = new SpringTemplateEngine();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        stubTemplateEngine.setTemplateResolver(resolver);
        return stubTemplateEngine;
    }

    private String extractText(byte[] pdf) throws Exception {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdf))) {
            return new PDFTextStripper().getText(document);
        }
    }

}
