package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReservationRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipLine;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipDto;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class PackagingSlipServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private PackagingSlipRepository packagingSlipRepository;
    @Mock
    private InventoryReservationRepository inventoryReservationRepository;
    @Mock
    private FinishedGoodRepository finishedGoodRepository;
    @Mock
    private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Mock
    private SalesOrderRepository salesOrderRepository;
    @Mock
    private InventoryValuationService inventoryValuationService;
    @Mock
    private BatchNumberService batchNumberService;

    private PackagingSlipService service;
    private Company company;

    @BeforeEach
    void setUp() {
        service = new PackagingSlipService(
                companyContextService,
                packagingSlipRepository,
                inventoryReservationRepository,
                finishedGoodRepository,
                finishedGoodBatchRepository,
                salesOrderRepository,
                inventoryValuationService,
                batchNumberService);
        company = new Company();
        company.setCode("ACME");
    }

    @Test
    void getPackagingSlip_mapsLogisticsAndDeliveryChallanArtifacts() {
        PackagingSlip slip = packagingSlip();
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(packagingSlipRepository.findByIdAndCompany(11L, company)).thenReturn(Optional.of(slip));

        PackagingSlipDto dto = service.getPackagingSlip(11L);

        assertThat(dto.id()).isEqualTo(11L);
        assertThat(dto.orderNumber()).isEqualTo("SO-11");
        assertThat(dto.dealerName()).isEqualTo("Dealer 11");
        assertThat(dto.lines()).hasSize(1);
        assertThat(dto.lines().getFirst().productCode()).isEqualTo("FG-11");
        assertThat(dto.transporterName()).isEqualTo("FastMove Logistics");
        assertThat(dto.driverName()).isEqualTo("Ayaan");
        assertThat(dto.vehicleNumber()).isEqualTo("MH12AB1234");
        assertThat(dto.challanReference()).isEqualTo("LR-7788");
        assertThat(dto.deliveryChallanNumber()).isEqualTo("DC-PS-11");
        assertThat(dto.deliveryChallanPdfPath()).isEqualTo("/api/v1/dispatch/slip/11/challan/pdf");
    }

    @Test
    void getPackagingSlipByOrder_rejectsMultiplePrimarySlips() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(packagingSlipRepository.findPrimarySlipsByOrderId(company, 22L))
                .thenReturn(List.of(packagingSlip(), packagingSlip()));

        assertThatThrownBy(() -> service.getPackagingSlipByOrder(22L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Multiple packaging slips found for order 22");
    }

    private PackagingSlip packagingSlip() {
        Dealer dealer = new Dealer();
        dealer.setName("Dealer 11");

        SalesOrder order = new SalesOrder();
        order.setDealer(dealer);
        order.setOrderNumber("SO-11");
        setField(order, "id", 21L);

        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setProductCode("FG-11");
        finishedGood.setName("Primer");

        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode("BATCH-11");
        setField(batch, "publicId", UUID.randomUUID());

        PackagingSlipLine line = new PackagingSlipLine();
        line.setFinishedGoodBatch(batch);
        line.setOrderedQuantity(new BigDecimal("5.00"));
        line.setShippedQuantity(new BigDecimal("4.00"));
        line.setBackorderQuantity(BigDecimal.ONE);
        line.setQuantity(new BigDecimal("5.00"));
        line.setNotes("Dispatch carefully");

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 11L);
        setField(slip, "publicId", UUID.randomUUID());
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-11");
        slip.setStatus("DISPATCHED");
        setField(slip, "createdAt", Instant.parse("2026-03-08T10:15:30Z"));
        slip.setConfirmedAt(Instant.parse("2026-03-08T10:20:30Z"));
        slip.setConfirmedBy("factory.user");
        slip.setDispatchedAt(Instant.parse("2026-03-08T10:20:30Z"));
        slip.setDispatchNotes("Delivered");
        slip.setJournalEntryId(101L);
        slip.setCogsJournalEntryId(202L);
        slip.setTransporterName("FastMove Logistics");
        slip.setDriverName("Ayaan");
        slip.setVehicleNumber("MH12AB1234");
        slip.setChallanReference("LR-7788");
        line.setPackagingSlip(slip);
        slip.getLines().add(line);
        return slip;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Class<?> type = target.getClass();
            while (type != null) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    field.set(target, value);
                    return;
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                }
            }
            throw new NoSuchFieldException(name);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }
}
