package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.service.GstService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipLine;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReservationRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationResponse;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationRequest;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class FinishedGoodsDispatchEngineCoverageTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private FinishedGoodRepository finishedGoodRepository;
    @Mock
    private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Mock
    private PackagingSlipRepository packagingSlipRepository;
    @Mock
    private InventoryMovementRepository inventoryMovementRepository;
    @Mock
    private InventoryReservationRepository inventoryReservationRepository;
    @Mock
    private SalesOrderRepository salesOrderRepository;
    @Mock
    private GstService gstService;
    @Mock
    private CompanyClock companyClock;
    @Mock
    private InventoryMovementRecorder movementRecorder;
    @Mock
    private FinishedGoodsReservationEngine reservationEngine;
    @Mock
    private PackagingSlipService packagingSlipService;
    @Mock
    private InventoryValuationService inventoryValuationService;

    private FinishedGoodsDispatchEngine engine;

    @BeforeEach
    void setUp() {
        engine = new FinishedGoodsDispatchEngine(
                companyContextService,
                finishedGoodRepository,
                finishedGoodBatchRepository,
                packagingSlipRepository,
                inventoryMovementRepository,
                inventoryReservationRepository,
                salesOrderRepository,
                gstService,
                companyClock,
                movementRecorder,
                reservationEngine,
                packagingSlipService,
                inventoryValuationService);
    }

    @Test
    void findLegacyDispatchMovements_returnsEmptyWhenCompanyIsMissing() throws Exception {
        Method method = FinishedGoodsDispatchEngine.class
                .getDeclaredMethod("findLegacyDispatchMovements", Company.class, Long.class);
        method.setAccessible(true);

        assertThat(method.invoke(engine, null, 10L)).isEqualTo(List.of());
    }

    @Test
    void findLegacyDispatchMovements_returnsEmptyWhenPackagingSlipIdIsMissing() throws Exception {
        Method method = FinishedGoodsDispatchEngine.class
                .getDeclaredMethod("findLegacyDispatchMovements", Company.class, Long.class);
        method.setAccessible(true);

        Company company = new Company();
        assertThat(method.invoke(engine, company, null)).isEqualTo(List.of());
    }

    @Test
    void applyDispatchLogistics_ignoresNullSlipAndRequest() throws Exception {
        Method method = FinishedGoodsDispatchEngine.class
                .getDeclaredMethod("applyDispatchLogistics",
                        com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip.class,
                        DispatchConfirmationRequest.class);
        method.setAccessible(true);

        DispatchConfirmationRequest request = new DispatchConfirmationRequest(
                10L,
                List.of(new DispatchConfirmationRequest.LineConfirmation(1L, BigDecimal.ONE, null)),
                "notes",
                "factory.user",
                null,
                "FastMove",
                "Ayaan",
                "MH12AB1234",
                "LR-900");

        assertThat(method.invoke(engine, null, request)).isNull();
        assertThat(method.invoke(engine, new Object[] {null, null})).isNull();
    }

    @Test
    void applyDispatchLogistics_trimsValuesAndNullsBlankSegments() throws Exception {
        Method method = FinishedGoodsDispatchEngine.class
                .getDeclaredMethod("applyDispatchLogistics", PackagingSlip.class, DispatchConfirmationRequest.class);
        method.setAccessible(true);

        PackagingSlip slip = new PackagingSlip();
        DispatchConfirmationRequest request = new DispatchConfirmationRequest(
                10L,
                List.of(new DispatchConfirmationRequest.LineConfirmation(1L, BigDecimal.ONE, null)),
                "notes",
                "factory.user",
                null,
                "  FastMove  ",
                "   ",
                "  MH12AB1234  ",
                "  LR-900  ");

        assertThat(method.invoke(engine, slip, request)).isNull();
        assertThat(slip.getTransporterName()).isEqualTo("FastMove");
        assertThat(slip.getDriverName()).isNull();
        assertThat(slip.getVehicleNumber()).isEqualTo("MH12AB1234");
        assertThat(slip.getChallanReference()).isEqualTo("LR-900");
    }

    @Test
    void buildDispatchConfirmationResponse_includesArtifactAndLogisticsMetadata() throws Exception {
        Method method = FinishedGoodsDispatchEngine.class
                .getDeclaredMethod("buildDispatchConfirmationResponse", PackagingSlip.class, Company.class);
        method.setAccessible(true);

        Company company = new Company();
        PackagingSlip slip = new PackagingSlip();
        ReflectionTestUtils.setField(slip, "id", 77L);
        slip.setCompany(company);
        slip.setSlipNumber("PS-77");
        slip.setStatus("DISPATCHED");
        slip.setConfirmedAt(Instant.parse("2026-03-09T08:15:30Z"));
        slip.setConfirmedBy("factory.user");
        slip.setTransporterName("FastMove");
        slip.setDriverName("Ayaan");
        slip.setVehicleNumber("MH12AB1234");
        slip.setChallanReference("LR-900");
        slip.setJournalEntryId(10L);
        slip.setCogsJournalEntryId(11L);

        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setProductCode("FG-1");
        finishedGood.setName("Primer");

        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);

        PackagingSlipLine line = new PackagingSlipLine();
        ReflectionTestUtils.setField(line, "id", 101L);
        line.setFinishedGoodBatch(batch);
        line.setOrderedQuantity(new BigDecimal("5.00"));
        line.setShippedQuantity(new BigDecimal("3.00"));
        line.setBackorderQuantity(new BigDecimal("2.00"));
        line.setUnitCost(new BigDecimal("12.50"));
        line.setNotes("Ship now");
        slip.getLines().add(line);

        when(packagingSlipService.resolveBackorderSlipIdForResponse(eq(slip), eq(company), eq(true))).thenReturn(88L);

        DispatchConfirmationResponse response = (DispatchConfirmationResponse) method.invoke(engine, slip, company);

        assertThat(response.packagingSlipId()).isEqualTo(77L);
        assertThat(response.backorderSlipId()).isEqualTo(88L);
        assertThat(response.transporterName()).isEqualTo("FastMove");
        assertThat(response.driverName()).isEqualTo("Ayaan");
        assertThat(response.vehicleNumber()).isEqualTo("MH12AB1234");
        assertThat(response.challanReference()).isEqualTo("LR-900");
        assertThat(response.deliveryChallanNumber()).isEqualTo("DC-PS-77");
        assertThat(response.deliveryChallanPdfPath()).isEqualTo("/api/v1/dispatch/slip/77/challan/pdf");
        assertThat(response.totalOrderedAmount()).isEqualByComparingTo("62.5000");
        assertThat(response.totalShippedAmount()).isEqualByComparingTo("37.5000");
        assertThat(response.totalBackorderAmount()).isEqualByComparingTo("25.0000");
        assertThat(response.lines()).singleElement().satisfies(result -> {
            assertThat(result.productCode()).isEqualTo("FG-1");
            assertThat(result.lineTotal()).isEqualByComparingTo("37.5000");
        });

        verify(packagingSlipService).resolveBackorderSlipIdForResponse(eq(slip), eq(company), eq(true));
    }

    @Test
    void applyDispatchLogistics_ignoresNullSlipOrRequest() throws Exception {
        Method method = FinishedGoodsDispatchEngine.class
                .getDeclaredMethod("applyDispatchLogistics", PackagingSlip.class, DispatchConfirmationRequest.class);
        method.setAccessible(true);

        PackagingSlip slip = new PackagingSlip();
        DispatchConfirmationRequest request = new DispatchConfirmationRequest(
                1L,
                List.of(new DispatchConfirmationRequest.LineConfirmation(1L, BigDecimal.ONE, null)),
                "notes",
                "override",
                2L,
                " FastMove ",
                " Ayaan ",
                " MH12AB1234 ",
                " LR-900 "
        );

        assertThatCode(() -> method.invoke(engine, null, request)).doesNotThrowAnyException();
        assertThatCode(() -> method.invoke(engine, slip, null)).doesNotThrowAnyException();
        assertThat(slip.getTransporterName()).isNull();
        assertThat(slip.getDriverName()).isNull();
        assertThat(slip.getVehicleNumber()).isNull();
        assertThat(slip.getChallanReference()).isNull();
    }
}
