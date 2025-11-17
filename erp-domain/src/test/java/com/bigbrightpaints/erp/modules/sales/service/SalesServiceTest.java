package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.sales.domain.*;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.event.SalesOrderCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private DealerRepository dealerRepository;
    @Mock
    private SalesOrderRepository salesOrderRepository;
    @Mock
    private PromotionRepository promotionRepository;
    @Mock
    private SalesTargetRepository salesTargetRepository;
    @Mock
    private CreditRequestRepository creditRequestRepository;
    @Mock
    private OrderNumberService orderNumberService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ProductionProductRepository productionProductRepository;
    @Mock
    private DealerLedgerService dealerLedgerService;
    @Mock
    private FinishedGoodRepository finishedGoodRepository;
    @Mock
    private AccountRepository accountRepository;

    private SalesService salesService;
    private Company company;

    @BeforeEach
    void setUp() {
        salesService = new SalesService(
                companyContextService,
                dealerRepository,
                salesOrderRepository,
                promotionRepository,
                salesTargetRepository,
                creditRequestRepository,
                orderNumberService,
                eventPublisher,
                productionProductRepository,
                dealerLedgerService,
                finishedGoodRepository,
                accountRepository);
        company = new Company();
        company.setCode("COMP");
        company.setTimezone("UTC");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void createOrderNeedsRevenueAccount() {
        setupProduct("SKU1", BigDecimal.valueOf(100), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU1");
        finishedGood.setRevenueAccountId(null);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU1"))
                .thenReturn(Optional.of(finishedGood));

        SalesOrderRequest request = new SalesOrderRequest(
                null,
                BigDecimal.valueOf(100),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU1", "Desc", BigDecimal.ONE, BigDecimal.valueOf(100), BigDecimal.ZERO)),
                "NONE",
                null);

        assertThrows(IllegalStateException.class, () -> salesService.createOrder(request));
    }

    @Test
    void createOrderRequiresTaxAccountWhenGstPerItem() {
        setupProduct("SKU2", BigDecimal.valueOf(100), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU2");
        finishedGood.setRevenueAccountId(1L);
        finishedGood.setTaxAccountId(null);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU2"))
                .thenReturn(Optional.of(finishedGood));

        SalesOrderRequest request = new SalesOrderRequest(
                null,
                BigDecimal.valueOf(110),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU2", "Desc", BigDecimal.ONE, BigDecimal.valueOf(100), BigDecimal.TEN)),
                "PER_ITEM",
                null);

        assertThrows(IllegalStateException.class, () -> salesService.createOrder(request));
    }

    @Test
    void createOrderRespectsDealerCreditLimit() {
        setupProduct("SKU3", BigDecimal.valueOf(200), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU3");
        finishedGood.setRevenueAccountId(5L);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU3"))
                .thenReturn(Optional.of(finishedGood));
        Dealer dealer = dealerWithCreditLimit(42L, BigDecimal.valueOf(1000));
        when(dealerRepository.findByCompanyAndId(company, 42L)).thenReturn(Optional.of(dealer));
        when(orderNumberService.nextOrderNumber(company)).thenReturn("SO-42");
        when(dealerLedgerService.currentBalance(42L)).thenReturn(BigDecimal.valueOf(950));

        SalesOrderRequest request = new SalesOrderRequest(
                42L,
                BigDecimal.valueOf(200),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU3", "Desc", BigDecimal.ONE, BigDecimal.valueOf(200), null)),
                "NONE",
                null);

        assertThrows(IllegalStateException.class, () -> salesService.createOrder(request));
    }

    @Test
    void createOrderUsesCompanyDefaultGstForOrderTotal() {
        company.setDefaultGstRate(BigDecimal.valueOf(15));
        setupProduct("SKU4", BigDecimal.valueOf(100), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU4");
        finishedGood.setRevenueAccountId(6L);
        finishedGood.setTaxAccountId(7L);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU4"))
                .thenReturn(Optional.of(finishedGood));
        when(orderNumberService.nextOrderNumber(company)).thenReturn("SO-100");
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class))).thenAnswer(invocation -> {
            SalesOrder entity = invocation.getArgument(0);
            setField(entity, "id", 501L);
            return entity;
        });

        SalesOrderRequest request = new SalesOrderRequest(
                null,
                BigDecimal.valueOf(115),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU4", "Desc", BigDecimal.ONE, BigDecimal.valueOf(100), null)),
                "ORDER_TOTAL",
                null);

        salesService.createOrder(request);

        ArgumentCaptor<SalesOrder> orderCaptor = ArgumentCaptor.forClass(SalesOrder.class);
        verify(salesOrderRepository).save(orderCaptor.capture());
        assertEquals(new BigDecimal("15.0000"), orderCaptor.getValue().getGstRate());
    }

    @Test
    void createOrderUsesCompanyDefaultGstForPerItemWhenMissing() {
        company.setDefaultGstRate(BigDecimal.valueOf(12));
        setupProduct("SKU5", BigDecimal.valueOf(50), BigDecimal.ZERO);
        FinishedGood finishedGood = buildFinishedGood("SKU5");
        finishedGood.setRevenueAccountId(8L);
        finishedGood.setTaxAccountId(9L);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "SKU5"))
                .thenReturn(Optional.of(finishedGood));
        when(orderNumberService.nextOrderNumber(company)).thenReturn("SO-200");
        when(salesOrderRepository.save(ArgumentMatchers.any(SalesOrder.class))).thenAnswer(invocation -> {
            SalesOrder entity = invocation.getArgument(0);
            setField(entity, "id", 601L);
            return entity;
        });

        SalesOrderRequest request = new SalesOrderRequest(
                null,
                BigDecimal.valueOf(56),
                "INR",
                null,
                List.of(new SalesOrderItemRequest("SKU5", "Desc", BigDecimal.ONE, BigDecimal.valueOf(50), null)),
                "PER_ITEM",
                null);

        salesService.createOrder(request);

        ArgumentCaptor<SalesOrder> orderCaptor = ArgumentCaptor.forClass(SalesOrder.class);
        verify(salesOrderRepository).save(orderCaptor.capture());
        assertEquals(new BigDecimal("12.0000"), orderCaptor.getValue().getItems().get(0).getGstRate());
    }

    private void setupProduct(String sku, BigDecimal price, BigDecimal gstRate) {
        ProductionProduct product = new ProductionProduct();
        product.setSkuCode(sku);
        product.setBasePrice(price);
        product.setGstRate(gstRate);
        ProductionBrand brand = new ProductionBrand();
        brand.setCompany(company);
        brand.setCode("BR");
        brand.setName("Brand");
        product.setBrand(brand);
        product.setCompany(company);
        when(productionProductRepository.findByCompanyAndSkuCode(company, sku))
                .thenReturn(Optional.of(product));
    }

    private FinishedGood buildFinishedGood(String sku) {
        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setCompany(company);
        finishedGood.setProductCode(sku);
        finishedGood.setName("Name");
        finishedGood.setCurrentStock(BigDecimal.ZERO);
        finishedGood.setReservedStock(BigDecimal.ZERO);
        finishedGood.setValuationAccountId(10L);
        finishedGood.setCogsAccountId(11L);
        return finishedGood;
    }

    private Dealer dealerWithCreditLimit(long id, BigDecimal limit) {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Dealer");
        dealer.setCreditLimit(limit);
        setField(dealer, "id", id);
        return dealer;
    }

    private void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }
    }
}
