package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.service.GstService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReservationRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationRequest;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
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
}
