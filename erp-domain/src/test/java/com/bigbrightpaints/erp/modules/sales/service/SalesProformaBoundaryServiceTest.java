package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.CreditLimitExceededException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTask;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTaskRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import org.junit.jupiter.api.Tag;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class SalesProformaBoundaryServiceTest {

    @Mock
    private DealerRepository dealerRepository;

    @Mock
    private DealerLedgerService dealerLedgerService;

    @Mock
    private SalesOrderRepository salesOrderRepository;

    @Mock
    private FinishedGoodRepository finishedGoodRepository;

    @Mock
    private FinishedGoodBatchRepository finishedGoodBatchRepository;

    @Mock
    private FactoryTaskRepository factoryTaskRepository;

    @Mock
    private CompanyClock companyClock;

    private SalesProformaBoundaryService service;
    private Company company;

    @BeforeEach
    void setUp() {
        service = new SalesProformaBoundaryService(
                dealerRepository,
                dealerLedgerService,
                salesOrderRepository,
                finishedGoodRepository,
                finishedGoodBatchRepository,
                factoryTaskRepository,
                companyClock);
        company = new Company();
        company.setName("BigBright");
        company.setCode("BBP");
        company.setTimezone("UTC");
        setField(company, "id", 99L);
    }

    @Test
    void normalizePaymentMode_mapsLegacyHybridAndRejectsUnsupportedValues() {
        assertThat(service.normalizePaymentMode(null)).isEqualTo("CREDIT");
        assertThat(service.normalizePaymentMode(" split ")).isEqualTo("HYBRID");
        assertThat(service.requiresCreditCheck("CASH")).isFalse();
        assertThat(service.requiresCreditCheck("HYBRID")).isTrue();

        assertThatThrownBy(() -> service.normalizePaymentMode("WIRE"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Unsupported sales order payment mode: WIRE")
                .extracting(ex -> ((ApplicationException) ex).getDetails())
                .satisfies(details -> assertThat(details)
                        .containsEntry("paymentMode", "WIRE")
                        .containsKey("allowedPaymentModes")
                        .containsKey("legacyAliases"));
    }

    @Test
    void resolveDealerForProforma_returnsNullForMissingDealerIdAndRejectsOnHoldDealer() {
        assertThat(service.resolveDealerForProforma(company, null, "CREDIT")).isNull();

        Dealer dealer = dealer(14L, "Dealer Hold", new BigDecimal("1000.00"));
        dealer.setStatus("ON_HOLD");
        when(dealerRepository.lockByCompanyAndId(company, 14L)).thenReturn(Optional.of(dealer));

        assertThatThrownBy(() -> service.resolveDealerForProforma(company, 14L, "CREDIT"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("is on hold");
    }

    @Test
    void enforceCreditPosture_requiresReceivableAccountForCreditBackedProformas() {
        Dealer dealer = dealer(41L, "Dealer Credit", new BigDecimal("500.00"));
        dealer.setReceivableAccount(null);
        when(dealerRepository.lockByCompanyAndId(company, 41L)).thenReturn(Optional.of(dealer));

        assertThatThrownBy(() -> service.enforceCreditPosture(company, dealer, new BigDecimal("50.00"), "CREDIT", null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("receivable account is required")
                .extracting(ex -> ((ApplicationException) ex).getDetails())
                .satisfies(details -> assertThat(details)
                        .containsEntry("dealerId", 41L)
                        .containsEntry("paymentMode", "CREDIT"));
    }

    @Test
    void enforceCreditPosture_raisesDetailedCreditLimitExceptionWhenExposureWouldOverflow() {
        Dealer dealer = dealer(42L, "Dealer Overflow", new BigDecimal("1000.00"));
        dealer.setReceivableAccount(account());
        when(dealerRepository.lockByCompanyAndId(company, 42L)).thenReturn(Optional.of(dealer));
        when(dealerLedgerService.currentBalance(42L)).thenReturn(new BigDecimal("650.00"));
        when(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(
                eq(company),
                eq(dealer),
                any(),
                eq(77L)))
                .thenReturn(new BigDecimal("300.00"));

        assertThatThrownBy(() -> service.enforceCreditPosture(company, dealer, new BigDecimal("200.00"), "HYBRID", 77L))
                .isInstanceOf(CreditLimitExceededException.class)
                .hasMessageContaining("HYBRID payment mode would exceed dealer Dealer Overflow credit posture")
                .extracting(ex -> ((CreditLimitExceededException) ex).getDetails())
                .satisfies(details -> assertThat(details)
                        .containsEntry("paymentMode", "HYBRID")
                        .containsEntry("outstandingBalance", new BigDecimal("650.00"))
                        .containsEntry("pendingOrderExposure", new BigDecimal("300.00"))
                        .containsEntry("projectedExposure", new BigDecimal("1150.00"))
                        .containsEntry("requiredHeadroom", new BigDecimal("150.00"))
                        .containsEntry("approvalRequired", true));
    }

    @Test
    void assessCommercialAvailability_syncsRequirementTiersAndCancelsOnlyOpenStaleTasks() {
        SalesOrder order = order(501L, "SO-501");
        order.getItems().add(item(order, "SKU-HIGH", "High Shortage", new BigDecimal("120")));
        order.getItems().add(item(order, "SKU-MID", "Mid Shortage", new BigDecimal("60")));
        order.getItems().add(item(order, "SKU-LOW", "Low Shortage", new BigDecimal("10")));

        FinishedGood midGood = new FinishedGood();
        midGood.setProductCode("SKU-MID");
        midGood.setName("Mid Shortage");

        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU-HIGH")).thenReturn(Optional.empty());
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU-MID")).thenReturn(Optional.of(midGood));
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU-LOW")).thenReturn(Optional.empty());
        when(finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(midGood))
                .thenReturn(List.of(batch(new BigDecimal("10"))));

        FactoryTask existingMid = task("Production requirement: SKU-MID", "IN_PROGRESS");
        FactoryTask staleOpen = task("Production requirement: STALE-SKU", "PENDING");
        FactoryTask closed = task("Production requirement: CLOSED-SKU", "COMPLETED");
        FactoryTask unmanaged = task("Non-managed task", "PENDING");
        when(factoryTaskRepository.findByCompanyAndSalesOrderId(company, 501L))
                .thenReturn(List.of(existingMid, staleOpen, closed, unmanaged));
        when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 3, 9));

        SalesProformaBoundaryService.CommercialAssessment assessment = service.assessCommercialAvailability(company, order);

        assertThat(assessment.commercialStatus()).isEqualTo("PENDING_PRODUCTION");
        assertThat(assessment.shortages())
                .extracting(FinishedGoodsService.InventoryShortage::productCode)
                .containsExactly("SKU-HIGH", "SKU-MID", "SKU-LOW");

        ArgumentCaptor<List<FactoryTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(factoryTaskRepository).saveAll(captor.capture());
        Map<String, FactoryTask> byTitle = captor.getValue().stream()
                .collect(java.util.stream.Collectors.toMap(FactoryTask::getTitle, task -> task));

        assertThat(byTitle.get("Production requirement: SKU-HIGH").getDueDate())
                .isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(byTitle.get("Production requirement: SKU-MID").getDueDate())
                .isEqualTo(LocalDate.of(2026, 3, 12));
        assertThat(byTitle.get("Production requirement: SKU-LOW").getDueDate())
                .isEqualTo(LocalDate.of(2026, 3, 16));
        assertThat(byTitle.get("Production requirement: STALE-SKU").getStatus()).isEqualTo("CANCELLED");
        assertThat(byTitle).doesNotContainKey("Production requirement: CLOSED-SKU");
    }

    @Test
    void openProductionRequirementSkus_returnsOnlyManagedOpenRequirementSkus() {
        when(factoryTaskRepository.findByCompanyAndSalesOrderId(company, 900L)).thenReturn(List.of(
                task("Production requirement: SKU-1", "PENDING"),
                task("Production requirement: SKU-2", "cancelled"),
                task("Adhoc task", "PENDING")
        ));

        assertThat(service.openProductionRequirementSkus(company, null)).isEmpty();
        assertThat(service.openProductionRequirementSkus(company, 900L)).containsExactly("SKU-1");
        verifyNoInteractions(dealerLedgerService);
    }

    @Test
    void assessCommercialAvailability_ignoresBlankSkusAndNonPositiveQuantities() {
        SalesOrder order = order(777L, "SO-777");
        order.getItems().add(item(order, " ", "No SKU", new BigDecimal("10")));
        order.getItems().add(item(order, "SKU-ZERO", null, BigDecimal.ZERO));

        SalesProformaBoundaryService.CommercialAssessment assessment = service.assessCommercialAvailability(company, order);

        assertThat(assessment.commercialStatus()).isEqualTo("RESERVED");
        assertThat(assessment.shortages()).isEmpty();
        verifyNoInteractions(finishedGoodRepository, finishedGoodBatchRepository);
    }

    @Test
    void helperMethods_handleNullAndBlankValues() throws Exception {
        Method isManagedRequirement = SalesProformaBoundaryService.class
                .getDeclaredMethod("isManagedRequirement", FactoryTask.class);
        Method isClosedRequirement = SalesProformaBoundaryService.class
                .getDeclaredMethod("isClosedRequirement", FactoryTask.class);
        Method extractSku = SalesProformaBoundaryService.class
                .getDeclaredMethod("extractSku", String.class);
        Method safe = SalesProformaBoundaryService.class.getDeclaredMethod("safe", BigDecimal.class);
        isManagedRequirement.setAccessible(true);
        isClosedRequirement.setAccessible(true);
        extractSku.setAccessible(true);
        safe.setAccessible(true);

        FactoryTask nullTitleTask = task(null, "PENDING");
        FactoryTask closedTask = task("Production requirement: sku-closed", " completed ");
        FactoryTask nullStatusTask = task("Production requirement: sku-open", null);

        assertThat(isManagedRequirement.invoke(service, new Object[] {null})).isEqualTo(false);
        assertThat(isManagedRequirement.invoke(service, nullTitleTask)).isEqualTo(false);
        assertThat(isManagedRequirement.invoke(service, closedTask)).isEqualTo(true);

        assertThat(isClosedRequirement.invoke(service, new Object[] {null})).isEqualTo(false);
        assertThat(isClosedRequirement.invoke(service, nullStatusTask)).isEqualTo(false);
        assertThat(isClosedRequirement.invoke(service, closedTask)).isEqualTo(true);

        assertThat(extractSku.invoke(service, new Object[] {null})).isEqualTo("UNKNOWN");
        assertThat(extractSku.invoke(service, "   ")).isEqualTo("UNKNOWN");
        assertThat(extractSku.invoke(service, "Production requirement: sku-closed")).isEqualTo("SKU-CLOSED");

        assertThat(safe.invoke(service, new Object[] {null})).isEqualTo(BigDecimal.ZERO);
        assertThat(safe.invoke(service, new BigDecimal("7.50"))).isEqualTo(new BigDecimal("7.50"));
    }

    @Test
    void enforceCreditPosture_returnsWhenDealerIsMissingAndRejectsZeroCreditLimit() {
        service.enforceCreditPosture(company, null, new BigDecimal("10.00"), "CREDIT", null);

        Dealer dealer = dealer(43L, "Dealer Zero", BigDecimal.ZERO);
        dealer.setReceivableAccount(account());
        when(dealerRepository.lockByCompanyAndId(company, 43L)).thenReturn(Optional.of(dealer));

        service.enforceCreditPosture(company, dealer, new BigDecimal("10.00"), "CREDIT", null);
    }

    private Dealer dealer(Long id, String name, BigDecimal creditLimit) {
        Dealer dealer = new Dealer();
        setField(dealer, "id", id);
        dealer.setCompany(company);
        dealer.setName(name);
        dealer.setCode(name.toUpperCase().replace(' ', '-'));
        dealer.setStatus("ACTIVE");
        dealer.setCreditLimit(creditLimit);
        return dealer;
    }

    private Account account() {
        Account account = new Account();
        setField(account, "id", 901L);
        account.setCode("AR-1");
        account.setName("Accounts Receivable");
        return account;
    }

    private SalesOrder order(Long id, String orderNumber) {
        SalesOrder order = new SalesOrder();
        setField(order, "id", id);
        order.setCompany(company);
        order.setOrderNumber(orderNumber);
        return order;
    }

    private SalesOrderItem item(SalesOrder order, String sku, String description, BigDecimal quantity) {
        SalesOrderItem item = new SalesOrderItem();
        item.setSalesOrder(order);
        item.setProductCode(sku);
        item.setDescription(description);
        item.setQuantity(quantity);
        return item;
    }

    private FinishedGoodBatch batch(BigDecimal availableQuantity) {
        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setQuantityAvailable(availableQuantity);
        return batch;
    }

    private FactoryTask task(String title, String status) {
        FactoryTask task = new FactoryTask();
        task.setCompany(company);
        task.setTitle(title);
        task.setStatus(status);
        return task;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }
}
