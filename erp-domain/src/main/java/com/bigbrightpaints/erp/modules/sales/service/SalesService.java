package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.CreditLimitExceededException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.*;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceNumberService;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTask;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTaskRepository;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.sales.domain.*;
import com.bigbrightpaints.erp.modules.sales.dto.*;
import com.bigbrightpaints.erp.modules.sales.event.SalesOrderCreatedEvent;
import jakarta.transaction.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.stream.Collectors;

@Service
public class SalesService {
    private static final BigDecimal MAX_GST_RATE = new BigDecimal("28.00");

    private final CompanyContextService companyContextService;
    private final DealerRepository dealerRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final PromotionRepository promotionRepository;
    private final SalesTargetRepository salesTargetRepository;
    private final CreditRequestRepository creditRequestRepository;
    private final OrderNumberService orderNumberService;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductionProductRepository productionProductRepository;
    private final FinishedGoodRepository finishedGoodRepository;
    private final DealerLedgerService dealerLedgerService;
    private final AccountRepository accountRepository;
    private final CompanyEntityLookup companyEntityLookup;
    private final PackagingSlipRepository packagingSlipRepository;
    private final FinishedGoodsService finishedGoodsService;
    private final AccountingService accountingService;
    private final AccountingFacade accountingFacade;
    private final InvoiceNumberService invoiceNumberService;
    private final InvoiceRepository invoiceRepository;
    private final FactoryTaskRepository factoryTaskRepository;
    private final CompanyDefaultAccountsService companyDefaultAccountsService;
    private final CreditLimitOverrideService creditLimitOverrideService;

    public SalesService(CompanyContextService companyContextService,
                        DealerRepository dealerRepository,
                        SalesOrderRepository salesOrderRepository,
                        PromotionRepository promotionRepository,
                        SalesTargetRepository salesTargetRepository,
                        CreditRequestRepository creditRequestRepository,
                        OrderNumberService orderNumberService,
                        ApplicationEventPublisher eventPublisher,
                        ProductionProductRepository productionProductRepository,
                        DealerLedgerService dealerLedgerService,
                        FinishedGoodRepository finishedGoodRepository,
                        AccountRepository accountRepository,
                        CompanyEntityLookup companyEntityLookup,
                        PackagingSlipRepository packagingSlipRepository,
                        FinishedGoodsService finishedGoodsService,
                        AccountingService accountingService,
                        AccountingFacade accountingFacade,
                        InvoiceNumberService invoiceNumberService,
                        InvoiceRepository invoiceRepository,
                        FactoryTaskRepository factoryTaskRepository,
                        CompanyDefaultAccountsService companyDefaultAccountsService,
                        CreditLimitOverrideService creditLimitOverrideService) {
        this.companyContextService = companyContextService;
        this.dealerRepository = dealerRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.promotionRepository = promotionRepository;
        this.salesTargetRepository = salesTargetRepository;
        this.creditRequestRepository = creditRequestRepository;
        this.orderNumberService = orderNumberService;
        this.eventPublisher = eventPublisher;
        this.productionProductRepository = productionProductRepository;
        this.dealerLedgerService = dealerLedgerService;
        this.finishedGoodRepository = finishedGoodRepository;
        this.accountRepository = accountRepository;
        this.companyEntityLookup = companyEntityLookup;
        this.packagingSlipRepository = packagingSlipRepository;
        this.finishedGoodsService = finishedGoodsService;
        this.accountingService = accountingService;
        this.accountingFacade = accountingFacade;
        this.invoiceNumberService = invoiceNumberService;
        this.invoiceRepository = invoiceRepository;
        this.factoryTaskRepository = factoryTaskRepository;
        this.companyDefaultAccountsService = companyDefaultAccountsService;
        this.creditLimitOverrideService = creditLimitOverrideService;
    }

    /* Dealers */
    public List<DealerDto> listDealers() {
        Company company = companyContextService.requireCurrentCompany();
        List<Dealer> dealers = dealerRepository.findByCompanyOrderByNameAsc(company);
        List<Long> dealerIds = dealers.stream().map(Dealer::getId).toList();
        var balances = dealerLedgerService.currentBalances(dealerIds);
        return dealers.stream()
                .map(dealer -> toDto(dealer, balances.getOrDefault(dealer.getId(), BigDecimal.ZERO)))
                .toList();
    }

    @Transactional
    public DealerDto createDealer(DealerRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName(request.name());
        dealer.setCode(request.code());
        dealer.setEmail(request.email());
        dealer.setPhone(request.phone());
        dealer.setCreditLimit(request.creditLimit());
        Account receivableAccount = createReceivableAccount(company, dealer);
        dealer.setReceivableAccount(receivableAccount);
        return toDto(dealerRepository.save(dealer));
    }

    @Transactional
    public DealerDto updateDealer(Long id, DealerRequest request) {
        Dealer dealer = requireDealer(id);
        String oldCode = dealer.getCode();
        String oldName = dealer.getName();
        dealer.setName(request.name());
        dealer.setCode(request.code());
        dealer.setEmail(request.email());
        dealer.setPhone(request.phone());
        dealer.setCreditLimit(request.creditLimit());
        syncReceivableAccount(dealer, oldCode, request.code(), oldName, request.name());
        return toDto(dealer);
    }

    private void syncReceivableAccount(Dealer dealer, String oldCode, String newCode, String oldName, String newName) {
        Account receivableAccount = dealer.getReceivableAccount();
        if (receivableAccount == null) {
            return;
        }
        boolean changed = false;
        if (oldCode != null && !oldCode.equals(newCode)) {
            String oldAccountCode = receivableAccount.getCode();
            if (oldAccountCode != null && oldAccountCode.startsWith("AR-" + oldCode)) {
                String suffix = oldAccountCode.substring(("AR-" + oldCode).length());
                String newAccountCode = "AR-" + newCode + suffix;
                Company company = dealer.getCompany();
                if (accountRepository.findByCompanyAndCodeIgnoreCase(company, newAccountCode).isEmpty()) {
                    receivableAccount.setCode(newAccountCode);
                    changed = true;
                }
            }
        }
        if (oldName != null && !oldName.equals(newName)) {
            receivableAccount.setName(newName + " Receivable");
            changed = true;
        }
        if (changed) {
            accountRepository.save(receivableAccount);
        }
    }

    @Transactional
    public void deleteDealer(Long id) {
        Dealer dealer = requireDealer(id);
        Account receivableAccount = dealer.getReceivableAccount();
        if (receivableAccount != null) {
            receivableAccount.setActive(false);
            accountRepository.save(receivableAccount);
        }
        dealerRepository.delete(dealer);
    }

    private DealerDto toDto(Dealer dealer) {
        BigDecimal balance = dealer.getId() == null ? BigDecimal.ZERO : dealerLedgerService.currentBalance(dealer.getId());
        return toDto(dealer, balance);
    }

    private DealerDto toDto(Dealer dealer, BigDecimal outstandingBalance) {
        return new DealerDto(dealer.getId(), dealer.getPublicId(), dealer.getName(), dealer.getCode(), dealer.getEmail(),
                dealer.getPhone(), dealer.getStatus(), dealer.getCreditLimit(), outstandingBalance);
    }

    private Dealer requireDealer(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        return companyEntityLookup.requireDealer(company, id);
    }

    private Account createReceivableAccount(Company company, Dealer dealer) {
        String baseCode = "AR-" + dealer.getCode();
        String code = baseCode;
        int attempt = 1;
        while (accountRepository.findByCompanyAndCodeIgnoreCase(company, code).isPresent()) {
            code = baseCode + "-" + attempt++;
        }
        Account account = new Account();
        account.setCompany(company);
        account.setCode(code);
        account.setName(dealer.getName() + " Receivable");
        account.setType(AccountType.ASSET);
        return accountRepository.save(account);
    }

    /* Sales Orders */
    public List<SalesOrderDto> listOrders(String status) {
        Company company = companyContextService.requireCurrentCompany();
        List<SalesOrder> orders = (status == null || status.isBlank())
                ? salesOrderRepository.findByCompanyOrderByCreatedAtDesc(company)
                : salesOrderRepository.findByCompanyAndStatusOrderByCreatedAtDesc(company, status);
        return orders.stream().map(this::toDto).toList();
    }

    @org.springframework.transaction.annotation.Transactional(isolation = Isolation.REPEATABLE_READ)
    public SalesOrderDto createOrder(SalesOrderRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        String idempotencyKey = request.resolveIdempotencyKey();
        Optional<SalesOrder> existing = salesOrderRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey);
        if (existing.isPresent()) {
            return toDto(existing.get());
        }
        GstTreatment gstTreatment = resolveGstTreatment(request.gstTreatment());
        BigDecimal orderLevelRate = resolveOrderLevelRate(company, gstTreatment, request.gstRate());
        boolean gstInclusive = Boolean.TRUE.equals(request.gstInclusive());
        Dealer dealer = null;
        if (request.dealerId() != null) {
            // Lock dealer early to prevent concurrent credit limit races
            dealer = dealerRepository.lockByCompanyAndId(company, request.dealerId())
                    .orElseThrow(() -> new IllegalArgumentException("Dealer not found"));
            if ("ON_HOLD".equalsIgnoreCase(dealer.getStatus())) {
                throw new IllegalStateException("Dealer " + dealer.getName() + " is on hold");
            }
        }
        List<PricedOrderLine> items = resolveOrderItems(company, request.items(), gstTreatment, orderLevelRate);
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber(orderNumberService.nextOrderNumber(company));
        order.setStatus("BOOKED");
        order.setCurrency(request.currency() == null ? "INR" : request.currency());
        order.setNotes(request.notes());
        order.setIdempotencyKey(idempotencyKey);
        OrderAmountSummary amounts = mapOrderItems(order, items, gstTreatment, orderLevelRate, gstInclusive);
        validateTotalAmount(request.totalAmount(), amounts.total());
        enforceCreditLimit(order.getDealer(), amounts.total());
        SalesOrder saved = salesOrderRepository.save(order);

        // Reserve available stock - shortages will be handled as production tasks
        FinishedGoodsService.InventoryReservationResult reservationResult = finishedGoodsService.reserveForOrder(saved);

        // Determine order status based on reservation result
        if (reservationResult.shortages().isEmpty()) {
            // Full stock available - order is ready to dispatch
            saved.setStatus("RESERVED");
        } else {
            // Partial or no stock - create production tasks for shortages
            saved.setStatus("PENDING_PRODUCTION");
            Long packagingSlipId = reservationResult.packagingSlip() != null ? reservationResult.packagingSlip().id() : null;
            createShortageTasksForOrder(company, saved, reservationResult.shortages(), packagingSlipId);
        }
        salesOrderRepository.save(saved);

        eventPublisher.publishEvent(new SalesOrderCreatedEvent(saved.getId(), company.getCode(), saved.getTotalAmount()));
        return toDto(saved);
    }

    @Transactional
    public SalesOrderDto updateOrder(Long id, SalesOrderRequest request) {
        SalesOrder order = requireOrder(id);
        GstTreatment gstTreatment = resolveGstTreatment(request.gstTreatment());
        BigDecimal orderLevelRate = resolveOrderLevelRate(order.getCompany(), gstTreatment, request.gstRate());
        boolean gstInclusive = Boolean.TRUE.equals(request.gstInclusive());
        Dealer dealer = null;
        if (request.dealerId() != null) {
            dealer = requireDealer(request.dealerId());
            if ("ON_HOLD".equalsIgnoreCase(dealer.getStatus())) {
                throw new IllegalStateException("Dealer " + dealer.getName() + " is on hold");
            }
        }
        List<PricedOrderLine> items = resolveOrderItems(order.getCompany(), request.items(), gstTreatment, orderLevelRate);
        if (dealer != null) {
            order.setDealer(dealer);
        }
        order.setCurrency(request.currency());
        order.setNotes(request.notes());
        OrderAmountSummary amounts = mapOrderItems(order, items, gstTreatment, orderLevelRate, gstInclusive);
        validateTotalAmount(request.totalAmount(), amounts.total());
        return toDto(order);
    }

    public void deleteOrder(Long id) {
        SalesOrder order = requireOrder(id);
        salesOrderRepository.delete(order);
    }

    @Transactional
    public SalesOrderDto confirmOrder(Long id) {
        SalesOrder order = requireOrder(id);
        order.setStatus("CONFIRMED");
        return toDto(order);
    }

    @Transactional
    public SalesOrderDto cancelOrder(Long id, String reason) {
        SalesOrder order = requireOrder(id);
        String currentStatus = order.getStatus();

        // Release any reserved inventory before cancelling
        if ("RESERVED".equalsIgnoreCase(currentStatus) ||
            "BOOKED".equalsIgnoreCase(currentStatus) ||
            "CONFIRMED".equalsIgnoreCase(currentStatus) ||
            "PENDING_PRODUCTION".equalsIgnoreCase(currentStatus)) {
            finishedGoodsService.releaseReservationsForOrder(order.getId());
            cancelFactoryTasksForOrder(order);
        }

        order.setStatus("CANCELLED");
        order.setNotes(reason);
        return toDto(order);
    }

    @Transactional
    public SalesOrderDto updateStatus(Long id, String status) {
        SalesOrder order = requireOrder(id);
        order.setStatus(status);
        return toDto(order);
    }

    @Transactional
    public void attachTraceId(Long id, String traceId) {
        SalesOrder order = requireOrder(id);
        order.setTraceId(traceId);
    }

    private SalesOrder requireOrder(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        return companyEntityLookup.requireSalesOrder(company, id);
    }

    public SalesOrder getOrderWithItems(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        return salesOrderRepository.findWithItemsByCompanyAndId(company, id)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));
    }

    private GstTreatment resolveGstTreatment(String value) {
        if (!StringUtils.hasText(value)) {
            return GstTreatment.NONE;
        }
        String normalized = value.trim().toUpperCase();
        if ("EXCLUSIVE".equals(normalized)) {
            return GstTreatment.NONE;
        }
        if ("INCLUSIVE".equals(normalized)) {
            return GstTreatment.ORDER_TOTAL;
        }
        try {
            return GstTreatment.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown GST treatment " + value);
        }
    }

    private BigDecimal normalizePercent(BigDecimal rate) {
        if (rate == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal sanitized = rate.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        if (sanitized.compareTo(MAX_GST_RATE) > 0) {
            throw new IllegalArgumentException("Unsupported GST rate " + sanitized + "%. Max allowed is " + MAX_GST_RATE);
        }
        return sanitized.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal currency(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private SalesOrderDto toDto(SalesOrder order) {
        String dealerName = order.getDealer() != null ? order.getDealer().getName() : null;
        List<SalesOrderItemDto> items = order.getItems().stream()
                .map(this::toItemDto)
                .collect(Collectors.toList());
        return new SalesOrderDto(order.getId(), order.getPublicId(), order.getOrderNumber(), order.getStatus(),
                order.getTotalAmount(), order.getSubtotalAmount(), order.getGstTotal(), order.getGstRate(),
                order.getGstTreatment(), order.getGstRoundingAdjustment(), order.getCurrency(), dealerName,
                order.getTraceId(), order.getCreatedAt(), items);
    }

    private SalesOrderItemDto toItemDto(SalesOrderItem item) {
        return new SalesOrderItemDto(
                item.getId(),
                item.getProductCode(),
                item.getDescription(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getLineSubtotal(),
                item.getGstRate(),
                item.getGstAmount(),
                item.getLineTotal()
        );
    }

    private OrderAmountSummary mapOrderItems(SalesOrder order,
                                             List<PricedOrderLine> requests,
                                             GstTreatment gstTreatment,
                                             BigDecimal orderLevelRate,
                                             boolean gstInclusive) {
        order.getItems().clear();
        if (requests == null || requests.isEmpty()) {
            order.setSubtotalAmount(BigDecimal.ZERO);
            order.setGstTotal(BigDecimal.ZERO);
            order.setGstRate(BigDecimal.ZERO);
            order.setGstTreatment(gstTreatment.name());
            order.setGstRoundingAdjustment(BigDecimal.ZERO);
            order.setTotalAmount(BigDecimal.ZERO);
            return new OrderAmountSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        List<SalesOrderItem> items = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (PricedOrderLine pricedLine : requests) {
            SalesOrderItem item = new SalesOrderItem();
            item.setSalesOrder(order);
            item.setProductCode(pricedLine.product().getSkuCode());
            item.setDescription(pricedLine.description());
            item.setQuantity(pricedLine.quantity());
            item.setUnitPrice(pricedLine.unitPrice());
            BigDecimal lineSubtotal = currency(pricedLine.quantity().multiply(pricedLine.unitPrice()));
            item.setLineSubtotal(lineSubtotal);
            item.setLineTotal(lineSubtotal);
            item.setGstRate(BigDecimal.ZERO);
            item.setGstAmount(BigDecimal.ZERO);
            items.add(item);
            subtotal = subtotal.add(lineSubtotal);
        }
        order.getItems().addAll(items);

        BigDecimal rawSubtotal = subtotal; // capture original (possibly GST-inclusive) subtotal
        BigDecimal taxTotal = BigDecimal.ZERO;
        BigDecimal rounding = BigDecimal.ZERO;
        if (gstTreatment == GstTreatment.PER_ITEM) {
            subtotal = BigDecimal.ZERO; // recompute using post-tax-adjusted line subtotals
            for (int i = 0; i < items.size(); i++) {
                SalesOrderItem item = items.get(i);
                BigDecimal rate = normalizePercent(requests.get(i).gstRate());
                BigDecimal lineTax;
                if (gstInclusive && rate.compareTo(BigDecimal.ZERO) > 0) {
                    lineTax = currency(item.getLineSubtotal()
                            .multiply(rate)
                            .divide(new BigDecimal("100").add(rate), 6, RoundingMode.HALF_UP));
                    BigDecimal exclusive = currency(item.getLineSubtotal().subtract(lineTax));
                    item.setLineSubtotal(exclusive);
                    item.setGstRate(rate);
                    item.setGstAmount(lineTax);
                    item.setLineTotal(currency(exclusive.add(lineTax)));
                    subtotal = subtotal.add(exclusive);
                } else {
                    lineTax = currency(item.getLineSubtotal()
                            .multiply(rate)
                            .divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
                    item.setGstRate(rate);
                    item.setGstAmount(lineTax);
                    item.setLineTotal(currency(item.getLineSubtotal().add(lineTax)));
                    subtotal = subtotal.add(item.getLineSubtotal());
                }
                taxTotal = taxTotal.add(lineTax);
            }
            orderLevelRate = BigDecimal.ZERO;
        } else if (gstTreatment == GstTreatment.ORDER_TOTAL) {
            BigDecimal rate = normalizePercent(orderLevelRate);
            orderLevelRate = rate;
            BigDecimal targetTax;
            if (gstInclusive && rate.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal base = rawSubtotal.divide(BigDecimal.ONE.add(rate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)), 6, RoundingMode.HALF_UP);
                targetTax = currency(rawSubtotal.subtract(base));
                subtotal = currency(base);
            } else {
                subtotal = rawSubtotal;
                targetTax = currency(subtotal
                        .multiply(rate)
                        .divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
            }
            BigDecimal allocationBase = gstInclusive ? rawSubtotal : subtotal;
            if (allocationBase.compareTo(BigDecimal.ZERO) > 0 && targetTax.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal distributed = BigDecimal.ZERO;
                for (int i = 0; i < items.size(); i++) {
                    SalesOrderItem item = items.get(i);
                    if (allocationBase.compareTo(BigDecimal.ZERO) == 0) {
                        continue;
                    }
                    BigDecimal lineSubtotal = item.getLineSubtotal();
                    BigDecimal share = targetTax.multiply(lineSubtotal)
                            .divide(allocationBase, 6, RoundingMode.HALF_UP);
                    share = currency(share);
                    if (i == items.size() - 1) {
                        BigDecimal remainder = targetTax.subtract(distributed.add(share));
                        share = share.add(remainder);
                        rounding = rounding.add(remainder);
                    }
                    distributed = distributed.add(share);
                    item.setGstRate(rate);
                    item.setGstAmount(share);
                    if (gstInclusive) {
                        BigDecimal exclusive = currency(lineSubtotal.subtract(share));
                        item.setLineSubtotal(exclusive);
                        item.setLineTotal(currency(exclusive.add(share)));
                    } else {
                        item.setLineTotal(currency(lineSubtotal.add(share)));
                    }
                }
            } else {
                for (SalesOrderItem item : items) {
                    item.setGstRate(rate);
                    item.setGstAmount(BigDecimal.ZERO);
                    item.setLineTotal(item.getLineSubtotal());
                }
            }
            taxTotal = targetTax;
        } else {
            for (SalesOrderItem item : items) {
                item.setGstRate(BigDecimal.ZERO);
                item.setGstAmount(BigDecimal.ZERO);
                item.setLineTotal(item.getLineSubtotal());
            }
            orderLevelRate = BigDecimal.ZERO;
        }

        BigDecimal total = currency(subtotal.add(taxTotal));
        order.setSubtotalAmount(subtotal);
        order.setGstTotal(taxTotal);
        order.setGstTreatment(gstTreatment.name());
        order.setGstRate(orderLevelRate);
        order.setGstRoundingAdjustment(currency(rounding));
        order.setTotalAmount(total);
        return new OrderAmountSummary(subtotal, taxTotal, total, currency(rounding));
    }

    private BigDecimal resolveMinAllowedPrice(ProductionProduct product) {
        BigDecimal basePrice = Optional.ofNullable(product.getBasePrice()).orElse(BigDecimal.ZERO);
        BigDecimal minDiscountPercent = Optional.ofNullable(product.getMinDiscountPercent()).orElse(BigDecimal.ZERO);
        BigDecimal explicitFloor = Optional.ofNullable(product.getMinSellingPrice()).orElse(BigDecimal.ZERO);

        BigDecimal discountFloor = basePrice;
        if (basePrice.compareTo(BigDecimal.ZERO) > 0 && minDiscountPercent.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal discount = basePrice.multiply(minDiscountPercent)
                    .divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
            discountFloor = basePrice.subtract(discount);
        }
        if (discountFloor.compareTo(BigDecimal.ZERO) < 0) {
            discountFloor = BigDecimal.ZERO;
        }
        if (explicitFloor.compareTo(discountFloor) > 0) {
            return explicitFloor;
        }
        return discountFloor;
    }

    private List<PricedOrderLine> resolveOrderItems(Company company,
                                                    List<SalesOrderItemRequest> requests,
                                                    GstTreatment gstTreatment,
                                                    BigDecimal orderLevelRate) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("At least one order item is required");
        }
        List<PricedOrderLine> resolved = new ArrayList<>();
        for (SalesOrderItemRequest request : requests) {
            String sku = request.productCode() != null ? request.productCode().trim() : "";
            if (!StringUtils.hasText(sku)) {
                throw new IllegalArgumentException("SKU is required for each order item");
            }
            ProductionProduct product = productionProductRepository.findByCompanyAndSkuCode(company, sku)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown SKU " + sku));
            if (!product.isActive()) {
                throw new IllegalStateException("SKU " + sku + " is inactive");
            }
            FinishedGood finishedGood = finishedGoodRepository.findByCompanyAndProductCode(company, sku)
                    .orElseThrow(() -> new IllegalStateException("Finished good not configured for SKU " + sku));
            requireRevenueAccount(finishedGood);
            BigDecimal quantity = normalizePositive(request.quantity(), "quantity", sku);
            BigDecimal unitPrice = request.unitPrice() != null ? request.unitPrice() : product.getBasePrice();
            BigDecimal minAllowed = resolveMinAllowedPrice(product);
            if (minAllowed.compareTo(BigDecimal.ZERO) > 0 && unitPrice.compareTo(minAllowed) < 0) {
                throw new IllegalArgumentException(String.format(
                        "Unit price %.2f for SKU %s is below the minimum allowed %.2f.",
                        unitPrice, sku, minAllowed));
            }
            String description = StringUtils.hasText(request.description())
                    ? request.description().trim()
                    : product.getProductName();
            BigDecimal requestedRate = request.gstRate();
            BigDecimal gstRate = requestedRate != null ? requestedRate : product.getGstRate();
            if (requestedRate == null
                    && (gstRate == null || gstRate.compareTo(BigDecimal.ZERO) <= 0)
                    && company.getDefaultGstRate() != null) {
                gstRate = company.getDefaultGstRate();
            }
            BigDecimal normalizedRate = normalizePercent(gstRate);
            if (requiresTaxAccount(gstTreatment, orderLevelRate, normalizedRate)
                    && finishedGood.getTaxAccountId() == null) {
                throw new IllegalStateException("Finished good " + sku + " is missing a GST liability account");
            }
            resolved.add(new PricedOrderLine(product, description, quantity, unitPrice, normalizedRate));
        }
        return resolved;
    }

    private BigDecimal normalizePositive(BigDecimal value, String field, String sku) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Order item " + field + " must be positive for SKU " + sku);
        }
        return value;
    }

    private void validateTotalAmount(BigDecimal provided, BigDecimal computed) {
        if (provided == null) {
            return;
        }
        if (!MoneyUtils.withinTolerance(provided, computed, new BigDecimal("0.01"))) {
            throw new IllegalArgumentException(String.format(
                    "Order total %.2f does not match computed total %.2f", provided, computed));
        }
    }

    private void enforceCreditLimit(Dealer dealer, BigDecimal orderTotal) {
        if (dealer == null || dealer.getId() == null) {
            return;
        }
        Dealer lockedDealer = dealerRepository.lockByCompanyAndId(dealer.getCompany(), dealer.getId())
                .orElseThrow(() -> new IllegalArgumentException("Dealer not found"));
        BigDecimal limit = lockedDealer.getCreditLimit();
        if (limit == null || limit.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal outstanding = dealerLedgerService.currentBalance(lockedDealer.getId());
        if (outstanding == null) {
            outstanding = BigDecimal.ZERO;
        }
        BigDecimal total = orderTotal == null ? BigDecimal.ZERO : orderTotal;
        BigDecimal projected = outstanding.add(total);
        if (projected.compareTo(limit) > 0) {
            throw new IllegalStateException(String.format(
                    "Dealer %s credit limit exceeded. Limit %.2f, outstanding %.2f, attempted order %.2f",
                    lockedDealer.getName(),
                    limit,
                    outstanding,
                    total));
        }
    }

    /**
     * Create production tasks for stock shortages.
     * Tasks are prioritized based on shortage quantity - no pricing info shared with factory.
     */
    private void createShortageTasksForOrder(Company company,
                                             SalesOrder order,
                                             List<FinishedGoodsService.InventoryShortage> shortages,
                                             Long packagingSlipId) {
        for (FinishedGoodsService.InventoryShortage shortage : shortages) {
            BigDecimal shortageQty = shortage.shortageQuantity();

            // Determine urgency based on shortage quantity
            String urgencyPrefix;
            LocalDate dueDate;
            if (shortageQty.compareTo(new BigDecimal("100")) >= 0) {
                urgencyPrefix = "[URGENT] ";
                dueDate = LocalDate.now().plusDays(1); // Large shortage - due tomorrow
            } else if (shortageQty.compareTo(new BigDecimal("50")) >= 0) {
                urgencyPrefix = "[HIGH] ";
                dueDate = LocalDate.now().plusDays(3); // Medium shortage - 3 days
            } else {
                urgencyPrefix = "";
                dueDate = LocalDate.now().plusDays(7); // Small shortage - 1 week
            }

            FactoryTask task = new FactoryTask();
            task.setCompany(company);
            task.setTitle(urgencyPrefix + "Production required: " + shortage.productCode());
            task.setDescription(String.format(
                    "Order #%s requires production of %s (%s).\nQuantity needed: %s units",
                    order.getOrderNumber(),
                    shortage.productCode(),
                    shortage.productName(),
                    shortageQty
            ));
            task.setStatus("PENDING");
            task.setDueDate(dueDate);
            task.setSalesOrderId(order.getId());
            task.setPackagingSlipId(packagingSlipId);
            factoryTaskRepository.save(task);
        }
    }

    private void cancelFactoryTasksForOrder(SalesOrder order) {
        if (order == null || order.getId() == null) {
            return;
        }
        List<FactoryTask> tasks = factoryTaskRepository.findByCompanyAndSalesOrderId(order.getCompany(), order.getId());
        if (tasks.isEmpty()) {
            return;
        }
        for (FactoryTask task : tasks) {
            task.setStatus("CANCELLED");
        }
        factoryTaskRepository.saveAll(tasks);
    }

    private record PricedOrderLine(ProductionProduct product,
                                   String description,
                                   BigDecimal quantity,
                                   BigDecimal unitPrice,
                                   BigDecimal gstRate) {}

    private void requireRevenueAccount(FinishedGood finishedGood) {
        if (finishedGood.getRevenueAccountId() == null) {
            throw new IllegalStateException("Finished good " + finishedGood.getProductCode()
                    + " is missing a revenue account");
        }
    }

    private boolean requiresTaxAccount(GstTreatment treatment, BigDecimal orderLevelRate, BigDecimal lineRate) {
        return switch (treatment) {
            case NONE -> false;
            case PER_ITEM -> lineRate != null && lineRate.compareTo(BigDecimal.ZERO) > 0;
            case ORDER_TOTAL -> orderLevelRate != null && orderLevelRate.compareTo(BigDecimal.ZERO) > 0;
        };
    }

    private record OrderAmountSummary(BigDecimal subtotal,
                                      BigDecimal tax,
                                      BigDecimal total,
                                      BigDecimal rounding) {}

    private enum GstTreatment {
        NONE,
        PER_ITEM,
        ORDER_TOTAL
    }

    /* Promotions */
    public List<PromotionDto> listPromotions() {
        Company company = companyContextService.requireCurrentCompany();
        return promotionRepository.findByCompanyOrderByStartDateDesc(company).stream().map(this::toDto).toList();
    }

    @Transactional
    public PromotionDto createPromotion(PromotionRequest request) {
        Promotion promotion = new Promotion();
        promotion.setCompany(companyContextService.requireCurrentCompany());
        mapPromotion(promotion, request);
        return toDto(promotionRepository.save(promotion));
    }

    @Transactional
    public PromotionDto updatePromotion(Long id, PromotionRequest request) {
        Promotion promotion = requirePromotion(id);
        mapPromotion(promotion, request);
        return toDto(promotion);
    }

    public void deletePromotion(Long id) {
        promotionRepository.delete(requirePromotion(id));
    }

    private void mapPromotion(Promotion promotion, PromotionRequest request) {
        promotion.setName(request.name());
        promotion.setDescription(request.description());
        promotion.setDiscountType(request.discountType());
        promotion.setDiscountValue(request.discountValue());
        promotion.setStartDate(request.startDate());
        promotion.setEndDate(request.endDate());
        promotion.setStatus(request.status() == null ? promotion.getStatus() : request.status());
    }

    private PromotionDto toDto(Promotion promotion) {
        return new PromotionDto(promotion.getId(), promotion.getPublicId(), promotion.getName(), promotion.getDescription(),
                promotion.getDiscountType(), promotion.getDiscountValue(), promotion.getStartDate(), promotion.getEndDate(), promotion.getStatus());
    }

    private Promotion requirePromotion(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        return companyEntityLookup.requirePromotion(company, id);
    }

    /* Sales Targets */
    public List<SalesTargetDto> listTargets() {
        Company company = companyContextService.requireCurrentCompany();
        return salesTargetRepository.findByCompanyOrderByPeriodStartDesc(company).stream().map(this::toDto).toList();
    }

    @Transactional
    public SalesTargetDto createTarget(SalesTargetRequest request) {
        SalesTarget target = new SalesTarget();
        target.setCompany(companyContextService.requireCurrentCompany());
        mapTarget(target, request);
        return toDto(salesTargetRepository.save(target));
    }

    @Transactional
    public SalesTargetDto updateTarget(Long id, SalesTargetRequest request) {
        SalesTarget target = requireTarget(id);
        mapTarget(target, request);
        return toDto(target);
    }

    public void deleteTarget(Long id) {
        salesTargetRepository.delete(requireTarget(id));
    }

    private void mapTarget(SalesTarget target, SalesTargetRequest request) {
        target.setName(request.name());
        target.setPeriodStart(request.periodStart());
        target.setPeriodEnd(request.periodEnd());
        target.setTargetAmount(request.targetAmount());
        if (request.achievedAmount() != null) {
            target.setAchievedAmount(request.achievedAmount());
        }
        target.setAssignee(request.assignee());
    }

    private SalesTargetDto toDto(SalesTarget target) {
        return new SalesTargetDto(target.getId(), target.getPublicId(), target.getName(), target.getPeriodStart(),
                target.getPeriodEnd(), target.getTargetAmount(), target.getAchievedAmount(), target.getAssignee());
    }

    private SalesTarget requireTarget(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        return companyEntityLookup.requireSalesTarget(company, id);
    }

    /* Credit Requests */
    public List<CreditRequestDto> listCreditRequests() {
        Company company = companyContextService.requireCurrentCompany();
        return creditRequestRepository.findByCompanyOrderByCreatedAtDesc(company).stream().map(this::toDto).toList();
    }

    @Transactional
    public CreditRequestDto createCreditRequest(CreditRequestRequest request) {
        CreditRequest creditRequest = new CreditRequest();
        creditRequest.setCompany(companyContextService.requireCurrentCompany());
        if (request.dealerId() != null) {
            creditRequest.setDealer(requireDealer(request.dealerId()));
        }
        creditRequest.setAmountRequested(request.amountRequested());
        creditRequest.setReason(request.reason());
        creditRequest.setStatus(request.status() == null ? "PENDING" : request.status());
        return toDto(creditRequestRepository.save(creditRequest));
    }

    @Transactional
    public CreditRequestDto updateCreditRequest(Long id, CreditRequestRequest request) {
        CreditRequest creditRequest = requireCreditRequest(id);
        if (request.dealerId() != null) {
            creditRequest.setDealer(requireDealer(request.dealerId()));
        }
        creditRequest.setAmountRequested(request.amountRequested());
        creditRequest.setReason(request.reason());
        if (request.status() != null) {
            creditRequest.setStatus(request.status());
        }
        return toDto(creditRequest);
    }

    private CreditRequestDto toDto(CreditRequest request) {
        String dealerName = request.getDealer() != null ? request.getDealer().getName() : null;
        return new CreditRequestDto(request.getId(), request.getPublicId(), dealerName, request.getAmountRequested(),
                request.getStatus(), request.getReason(), request.getCreatedAt());
    }

    private CreditRequest requireCreditRequest(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        return companyEntityLookup.requireCreditRequest(company, id);
    }

    private BigDecimal resolveOrderLevelRate(Company company,
                                             GstTreatment gstTreatment,
                                             BigDecimal requestedRate) {
        if (gstTreatment != GstTreatment.ORDER_TOTAL) {
            return BigDecimal.ZERO;
        }
        BigDecimal source = requestedRate;
        if (source == null || source.compareTo(BigDecimal.ZERO) <= 0) {
            source = company.getDefaultGstRate();
        }
        BigDecimal normalized = normalizePercent(source);
        if (normalized.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("GST rate is required when gstTreatment is ORDER_TOTAL");
        }
        return normalized;
    }

    private void enforceDispatchCreditLimit(Company company,
                                            Dealer dealer,
                                            PackagingSlip slip,
                                            SalesOrder order,
                                            BigDecimal dispatchAmount,
                                            Long overrideRequestId) {
        if (dealer == null || dispatchAmount == null) {
            return;
        }
        BigDecimal limit = dealer.getCreditLimit();
        if (limit == null || limit.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal exposure = dealerLedgerService.currentBalance(dealer.getId());
        if (exposure == null) {
            exposure = BigDecimal.ZERO;
        }
        BigDecimal projected = exposure.add(dispatchAmount);
        if (projected.compareTo(limit) <= 0) {
            return;
        }
        boolean overrideApproved = creditLimitOverrideService.isOverrideApproved(
                overrideRequestId,
                company,
                dealer,
                slip,
                order,
                dispatchAmount
        );
        if (overrideApproved) {
            return;
        }
        BigDecimal requiredHeadroom = projected.subtract(limit);
        if (requiredHeadroom.compareTo(BigDecimal.ZERO) < 0) {
            requiredHeadroom = BigDecimal.ZERO;
        }
        CreditLimitExceededException ex = new CreditLimitExceededException(
                "Credit limit exceeded for dealer " + dealer.getName());
        ex.withDetail("dealerId", dealer.getId())
                .withDetail("companyId", company.getId())
                .withDetail("currentExposure", exposure)
                .withDetail("creditLimit", limit)
                .withDetail("dispatchAmount", dispatchAmount)
                .withDetail("requiredHeadroom", requiredHeadroom)
                .withDetail("overrideRequestId", overrideRequestId);
        throw ex;
    }

    /* Dispatch confirmation: inventory issue + COGS posting (AR/Invoice to be wired) */
    @org.springframework.transaction.annotation.Transactional(isolation = Isolation.SERIALIZABLE)
    public DispatchConfirmResponse confirmDispatch(DispatchConfirmRequest request) {
        PackagingSlip slip = null;
        Company company = companyContextService.requireCurrentCompany();
        // Ensure company defaults are configured before proceeding
        companyDefaultAccountsService.requireDefaults();
        if (request.packingSlipId() != null) {
            slip = packagingSlipRepository.findAndLockByIdAndCompany(request.packingSlipId(), company)
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Packing slip not found"));
        } else if (request.orderId() != null) {
            List<PackagingSlip> slips = packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, request.orderId());
            if (slips.isEmpty()) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Packing slip not found for order " + request.orderId());
            }
            if (slips.size() > 1) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Multiple packing slips found for order; provide packingSlipId");
            }
            Long slipId = slips.get(0).getId();
            slip = packagingSlipRepository.findAndLockByIdAndCompany(slipId, company)
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                            "Packing slip not found for order " + request.orderId()));
        } else {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "packingSlipId or orderId is required");
        }
        Long salesOrderId = slip.getSalesOrder() != null ? slip.getSalesOrder().getId() : request.orderId();
        if (salesOrderId == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Sales order is required on packing slip");
        }
        String slipNumber = slip.getSlipNumber();
        Invoice existingInvoice = null;
        if (slip.getInvoiceId() != null) {
            existingInvoice = invoiceRepository.findByCompanyAndId(company, slip.getInvoiceId()).orElse(null);
        }

        // Map request lines by slip line id for pricing/qty overrides
        Map<Long, DispatchConfirmRequest.DispatchLine> lineOverrides = new HashMap<>();
        if (request.lines() != null) {
            for (DispatchConfirmRequest.DispatchLine line : request.lines()) {
                if (line.lineId() != null) {
                    lineOverrides.put(line.lineId(), line);
                }
            }
        }
        Map<Long, BigDecimal> shipQtyByLineId = new HashMap<>();

        SalesOrder order = requireOrder(salesOrderId);
        boolean alreadyDispatched = "DISPATCHED".equalsIgnoreCase(slip.getStatus());
        if (alreadyDispatched) {
            Long existingInvoiceId = existingInvoice != null ? existingInvoice.getId() : slip.getInvoiceId();
            Long existingJeId = slip.getJournalEntryId();
            if (existingJeId == null && existingInvoice != null && existingInvoice.getJournalEntry() != null) {
                existingJeId = existingInvoice.getJournalEntry().getId();
            }
            boolean hasInvoice = existingInvoiceId != null;
            boolean hasArJournal = existingJeId != null;
            boolean hasCogsJournal = slip.getCogsJournalEntryId() != null
                    || (slipNumber != null && accountingFacade.hasCogsJournalFor(slipNumber));
            if (hasInvoice && hasArJournal && hasCogsJournal) {
                if (existingInvoice != null) {
                    dealerLedgerService.syncInvoiceLedger(existingInvoice, null);
                }
                String nextStatus = resolveOrderStatusAfterDispatch(company, order);
                if (!nextStatus.equalsIgnoreCase(order.getStatus())) {
                    order.setStatus(nextStatus);
                    salesOrderRepository.save(order);
                }
                return new DispatchConfirmResponse(slip.getId(), salesOrderId, existingInvoiceId, existingJeId, List.of(), true, List.of());
            }
        }
        
        // Validate order status - block dispatch for cancelled/on-hold orders
        String orderStatus = order.getStatus();
        if ("CANCELLED".equalsIgnoreCase(orderStatus) || "ON_HOLD".equalsIgnoreCase(orderStatus) ||
            "REJECTED".equalsIgnoreCase(orderStatus) || "CLOSED".equalsIgnoreCase(orderStatus)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Cannot dispatch order with status: " + orderStatus);
        }
        
        Dealer dealer = order.getDealer();
        if (dealer == null || dealer.getReceivableAccount() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Dealer with receivable account is required for dispatch");
        }
        // Lock dealer to prevent concurrent credit limit races during dispatch
        dealer = dealerRepository.lockByCompanyAndId(company, dealer.getId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Dealer not found"));

        // Build shipped financial lines from slip lines + overrides
        List<SalesOrderItem> sortedItems = new ArrayList<>(order.getItems());
        sortedItems.sort(Comparator.comparing(SalesOrderItem::getId, Comparator.nullsLast(Long::compareTo)));
        Map<String, Deque<OrderItemAllocation>> orderItemsByProduct = new HashMap<>();
        Map<String, BigDecimal> remainingQtyByProduct = new HashMap<>();
        for (SalesOrderItem item : sortedItems) {
            if (item.getProductCode() == null) {
                continue;
            }
            BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
            orderItemsByProduct
                    .computeIfAbsent(item.getProductCode(), k -> new ArrayDeque<>())
                    .add(new OrderItemAllocation(item, qty));
            remainingQtyByProduct.merge(item.getProductCode(), qty, BigDecimal::add);
        }
        Map<Long, BigDecimal> revenueByAccount = new HashMap<>();
        Map<Long, BigDecimal> taxByAccount = new HashMap<>();
        List<InvoiceLine> invoiceLines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;

        for (var slipLine : slip.getLines()) {
            DispatchConfirmRequest.DispatchLine override = lineOverrides.get(slipLine.getId());
            BigDecimal requestedShip;
            if (alreadyDispatched) {
                requestedShip = slipLine.getShippedQuantity() != null ? slipLine.getShippedQuantity() : slipLine.getQuantity();
            } else {
                requestedShip = override != null && override.shipQty() != null ? override.shipQty() : slipLine.getQuantity();
            }
            if (requestedShip == null) {
                requestedShip = BigDecimal.ZERO;
            }
            BigDecimal shipQty = requestedShip;
            FinishedGood fg = slipLine.getFinishedGoodBatch().getFinishedGood();
            if (!alreadyDispatched) {
                if (shipQty.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal onHand = fg.getCurrentStock() == null ? BigDecimal.ZERO : fg.getCurrentStock();
                    shipQty = requestedShip.min(onHand);
                    BigDecimal remaining = remainingQtyByProduct.getOrDefault(fg.getProductCode(), BigDecimal.ZERO);
                    if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                        shipQty = BigDecimal.ZERO;
                    } else if (shipQty.compareTo(remaining) > 0) {
                        shipQty = remaining;
                    }
                    if (shipQty.compareTo(BigDecimal.ZERO) <= 0 && requestedShip.compareTo(BigDecimal.ZERO) > 0
                            && onHand.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                                "Insufficient stock to dispatch " + fg.getProductCode());
                    }
                } else {
                    shipQty = BigDecimal.ZERO;
                }
                shipQtyByLineId.put(slipLine.getId(), shipQty);
            }
            if (shipQty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            Deque<OrderItemAllocation> allocations = orderItemsByProduct.get(fg.getProductCode());
            if (allocations == null || allocations.isEmpty()) {
                continue;
            }
            if (alreadyDispatched) {
                shipQty = requestedShip;
            }
            BigDecimal totalShipQty = shipQty;
            BigDecimal discountTotal = override != null && override.discount() != null ? override.discount() : BigDecimal.ZERO;
            BigDecimal discountRemaining = discountTotal;
            BigDecimal remainingToAllocate = shipQty;
            SalesOrderItem lastItem = null;
            while (remainingToAllocate.compareTo(BigDecimal.ZERO) > 0 && !allocations.isEmpty()) {
                OrderItemAllocation allocation = allocations.peekFirst();
                BigDecimal allocQty = allocation.remaining.min(remainingToAllocate);
                if (allocQty.compareTo(BigDecimal.ZERO) <= 0) {
                    allocations.removeFirst();
                    continue;
                }
                boolean lastAllocation = allocQty.compareTo(remainingToAllocate) == 0;
                SalesOrderItem item = allocation.item;
                lastItem = item;

                BigDecimal price = override != null && override.priceOverride() != null
                        ? override.priceOverride()
                        : item.getUnitPrice();
                BigDecimal taxRate = override != null && override.taxRate() != null
                        ? override.taxRate()
                        : (item.getGstRate() == null ? BigDecimal.ZERO : item.getGstRate());
                BigDecimal discount = BigDecimal.ZERO;
                if (discountTotal.compareTo(BigDecimal.ZERO) > 0) {
                    if (lastAllocation) {
                        discount = discountRemaining;
                    } else {
                        discount = discountTotal.multiply(allocQty)
                                .divide(totalShipQty, 6, RoundingMode.HALF_UP);
                        discountRemaining = discountRemaining.subtract(discount);
                    }
                }

                BigDecimal lineGross = price.multiply(allocQty);
                BigDecimal lineNet = lineGross.subtract(discount);
                BigDecimal lineTax;
                if (Boolean.TRUE.equals(override != null ? override.taxInclusive() : Boolean.FALSE)) {
                    BigDecimal divisor = BigDecimal.ONE.add(taxRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
                    if (divisor.signum() == 0) {
                        lineTax = BigDecimal.ZERO;
                    } else {
                        BigDecimal preTax = lineNet.divide(divisor, 6, RoundingMode.HALF_UP);
                        lineTax = lineNet.subtract(preTax);
                        lineNet = preTax;
                    }
                } else {
                    lineTax = lineNet.multiply(taxRate).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
                }
                BigDecimal lineTotal = lineNet.add(lineTax);

                subtotal = subtotal.add(lineNet);
                taxTotal = taxTotal.add(lineTax);

                if (fg.getRevenueAccountId() != null) {
                    revenueByAccount.merge(fg.getRevenueAccountId(), lineNet, BigDecimal::add);
                }
                if (fg.getTaxAccountId() != null && lineTax.compareTo(BigDecimal.ZERO) > 0) {
                    taxByAccount.merge(fg.getTaxAccountId(), lineTax, BigDecimal::add);
                }

                InvoiceLine invLine = new InvoiceLine();
                invLine.setProductCode(fg.getProductCode());
                invLine.setDescription(item.getDescription());
                invLine.setQuantity(allocQty);
                invLine.setUnitPrice(price);
                invLine.setTaxRate(taxRate);
                invLine.setLineTotal(lineTotal);
                invoiceLines.add(invLine);

                allocation.remaining = allocation.remaining.subtract(allocQty).max(BigDecimal.ZERO);
                if (allocation.remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    allocations.removeFirst();
                }
                remainingQtyByProduct.computeIfPresent(
                        fg.getProductCode(),
                        (key, value) -> value.subtract(allocQty).max(BigDecimal.ZERO));
                remainingToAllocate = remainingToAllocate.subtract(allocQty);
            }

            if (remainingToAllocate.compareTo(BigDecimal.ZERO) > 0 && lastItem != null) {
                BigDecimal price = override != null && override.priceOverride() != null
                        ? override.priceOverride()
                        : lastItem.getUnitPrice();
                BigDecimal taxRate = override != null && override.taxRate() != null
                        ? override.taxRate()
                        : (lastItem.getGstRate() == null ? BigDecimal.ZERO : lastItem.getGstRate());
                BigDecimal discount = discountRemaining;
                BigDecimal lineGross = price.multiply(remainingToAllocate);
                BigDecimal lineNet = lineGross.subtract(discount);
                BigDecimal lineTax;
                if (Boolean.TRUE.equals(override != null ? override.taxInclusive() : Boolean.FALSE)) {
                    BigDecimal divisor = BigDecimal.ONE.add(taxRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
                    if (divisor.signum() == 0) {
                        lineTax = BigDecimal.ZERO;
                    } else {
                        BigDecimal preTax = lineNet.divide(divisor, 6, RoundingMode.HALF_UP);
                        lineTax = lineNet.subtract(preTax);
                        lineNet = preTax;
                    }
                } else {
                    lineTax = lineNet.multiply(taxRate).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
                }
                BigDecimal lineTotal = lineNet.add(lineTax);

                subtotal = subtotal.add(lineNet);
                taxTotal = taxTotal.add(lineTax);
                if (fg.getRevenueAccountId() != null) {
                    revenueByAccount.merge(fg.getRevenueAccountId(), lineNet, BigDecimal::add);
                }
                if (fg.getTaxAccountId() != null && lineTax.compareTo(BigDecimal.ZERO) > 0) {
                    taxByAccount.merge(fg.getTaxAccountId(), lineTax, BigDecimal::add);
                }

                InvoiceLine invLine = new InvoiceLine();
                invLine.setProductCode(fg.getProductCode());
                invLine.setDescription(lastItem.getDescription());
                invLine.setQuantity(remainingToAllocate);
                invLine.setUnitPrice(price);
                invLine.setTaxRate(taxRate);
                invLine.setLineTotal(lineTotal);
                invoiceLines.add(invLine);
            }
        }

        BigDecimal totalAmount = subtotal.add(taxTotal);
        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "No shippable quantity available for dispatch");
        }

        if (!Boolean.TRUE.equals(request.adminOverrideCreditLimit())) {
            enforceDispatchCreditLimit(company, dealer, slip, order, totalAmount, request.overrideRequestId());
        }

        if (!alreadyDispatched) {
            List<com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationRequest.LineConfirmation> confirmations =
                    slip.getLines().stream()
                            .map(line -> new com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationRequest.LineConfirmation(
                                    line.getId(),
                                    shipQtyByLineId.getOrDefault(line.getId(), BigDecimal.ZERO),
                                    lineOverrides.get(line.getId()) != null
                                            ? lineOverrides.get(line.getId()).notes()
                                            : null))
                            .toList();
            finishedGoodsService.confirmDispatch(
                    new com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationRequest(
                            slip.getId(),
                            confirmations,
                            request.dispatchNotes(),
                            request.confirmedBy(),
                            request.overrideRequestId()
                    ),
                    request.confirmedBy() != null ? request.confirmedBy() : "system"
            );
        }

        // Post inventory/COGS using slip line quantities (not reservation quantities)
        Map<String, BigDecimal> costByPair = new HashMap<>();
        Map<String, Long[]> accountsByPair = new HashMap<>();
        for (var slipLine : slip.getLines()) {
            BigDecimal shippedQty = slipLine.getShippedQuantity();
            if (shippedQty == null) {
                shippedQty = shipQtyByLineId.getOrDefault(slipLine.getId(), slipLine.getQuantity());
            }
            if (shippedQty == null || shippedQty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            FinishedGood fg = slipLine.getFinishedGoodBatch().getFinishedGood();
            if (fg == null) {
                continue;
            }
            Long inventoryAccountId = fg.getValuationAccountId();
            Long cogsAccountId = fg.getCogsAccountId();
            if (inventoryAccountId == null || cogsAccountId == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Finished good " + fg.getProductCode() + " missing accounting configuration");
            }
            BigDecimal unitCost = slipLine.getUnitCost();
            if (unitCost == null) {
                unitCost = slipLine.getFinishedGoodBatch() != null
                        ? slipLine.getFinishedGoodBatch().getUnitCost()
                        : BigDecimal.ZERO;
            }
            if (unitCost == null) {
                unitCost = BigDecimal.ZERO;
            }
            BigDecimal cost = unitCost.multiply(shippedQty);
            if (cost.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String key = cogsAccountId + ":" + inventoryAccountId;
            costByPair.merge(key, cost, BigDecimal::add);
            accountsByPair.putIfAbsent(key, new Long[]{cogsAccountId, inventoryAccountId});
        }
        List<FinishedGoodsService.DispatchPosting> postings = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : costByPair.entrySet()) {
            Long[] accounts = accountsByPair.get(entry.getKey());
            postings.add(new FinishedGoodsService.DispatchPosting(accounts[1], accounts[0], entry.getValue()));
        }
        List<DispatchConfirmResponse.CogsPostingDto> cogsDtos = postings.stream()
                .map(p -> new DispatchConfirmResponse.CogsPostingDto(p.inventoryAccountId(), p.cogsAccountId(), p.cost()))
                .toList();

        // Post AR/Revenue/Tax journal
        // Post COGS/Inventory for dispatched quantities
        final LocalDate dispatchedDate = slip.getDispatchedAt() != null
                ? LocalDate.ofInstant(slip.getDispatchedAt(), ZoneId.of(company.getTimezone()))
                : currentDate(company);

        Long cogsJournalId = null;
        if (postings != null && !postings.isEmpty()) {
            Map<String, BigDecimal> cogsCostByPair = new HashMap<>();
            Map<String, Long[]> cogsAccountsByPair = new HashMap<>();
            for (FinishedGoodsService.DispatchPosting posting : postings) {
                if (posting.cogsAccountId() == null || posting.inventoryAccountId() == null) {
                    continue;
                }
                String key = posting.cogsAccountId() + ":" + posting.inventoryAccountId();
                cogsCostByPair.merge(key, posting.cost(), BigDecimal::add);
                cogsAccountsByPair.putIfAbsent(key, new Long[]{posting.cogsAccountId(), posting.inventoryAccountId()});
            }
            BigDecimal totalCost = BigDecimal.ZERO;
            List<com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest.JournalLineRequest> cogsLines = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> entry : cogsCostByPair.entrySet()) {
                Long[] acc = cogsAccountsByPair.get(entry.getKey());
                BigDecimal cost = entry.getValue();
                if (cost == null || cost.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                totalCost = totalCost.add(cost);
                cogsLines.add(new com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest.JournalLineRequest(
                        acc[0], "COGS for dispatch " + slip.getSlipNumber(), cost, BigDecimal.ZERO));
                cogsLines.add(new com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest.JournalLineRequest(
                        acc[1], "Inventory relief for dispatch " + slip.getSlipNumber(), BigDecimal.ZERO, cost));
            }
            if (!cogsLines.isEmpty()) {
                var cogsEntry = accountingFacade.postCogsJournal(
                        slipNumber,
                        dealer.getId(),
                        dispatchedDate,
                        "COGS for dispatch " + slipNumber,
                        cogsLines
                );
                cogsJournalId = cogsEntry != null ? cogsEntry.id() : null;
            }
        }

        String invoiceNumber = existingInvoice != null
                ? existingInvoice.getInvoiceNumber()
                : invoiceNumberService.nextInvoiceNumber(company);

        Long arJournalEntryId = existingInvoice != null && existingInvoice.getJournalEntry() != null
                ? existingInvoice.getJournalEntry().getId()
                : null;
        List<DispatchConfirmResponse.AccountPostingDto> arPostings = new ArrayList<>();
        if (arJournalEntryId == null && totalAmount.compareTo(BigDecimal.ZERO) > 0) {
            if (revenueByAccount.isEmpty()) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "No revenue or tax account configured for dispatched items");
            }
            LocalDate dispatchDate = dispatchedDate;
            arPostings.add(toPosting(dealer.getReceivableAccount().getId(), "AR for dispatch " + slipNumber, totalAmount, BigDecimal.ZERO));
            for (var entry : revenueByAccount.entrySet()) {
                arPostings.add(toPosting(entry.getKey(), "Revenue for dispatch " + slipNumber, BigDecimal.ZERO, entry.getValue()));
            }
            for (var entry : taxByAccount.entrySet()) {
                arPostings.add(toPosting(entry.getKey(), "Tax for dispatch " + slipNumber, BigDecimal.ZERO, entry.getValue()));
            }
            var arEntry = accountingFacade.postSalesJournal(
                    dealer.getId(),
                    order.getOrderNumber(),
                    dispatchDate,
                    "Dispatch " + slipNumber,
                    revenueByAccount,
                    taxByAccount,
                    totalAmount,
                    invoiceNumber
            );
            arJournalEntryId = arEntry != null ? arEntry.id() : null;
        }

        // Create final invoice for shipped qty
        Invoice invoice = existingInvoice != null ? existingInvoice : new Invoice();
        boolean newInvoice = invoice.getId() == null;
        if (newInvoice) {
            invoice.setCompany(company);
            invoice.setDealer(dealer);
            invoice.setSalesOrder(order);
            invoice.setInvoiceNumber(invoiceNumber);
            invoice.setCurrency(order.getCurrency());
            invoice.setIssueDate(dispatchedDate);
            invoice.setDueDate(dispatchedDate.plusDays(15));
            invoice.setStatus("ISSUED");
            invoice.setSubtotal(subtotal);
            invoice.setTaxTotal(taxTotal);
            invoice.setTotalAmount(totalAmount);
            invoice.setOutstandingAmount(totalAmount);
            invoice.setNotes("Dispatch " + slipNumber);
            for (InvoiceLine line : invoiceLines) {
                line.setInvoice(invoice);
                invoice.getLines().add(line);
            }
        }
        if (arJournalEntryId != null && (invoice.getJournalEntry() == null || !arJournalEntryId.equals(invoice.getJournalEntry().getId()))) {
            invoice.setJournalEntry(companyEntityLookup.requireJournalEntry(company, arJournalEntryId));
        }
        invoice = invoiceRepository.save(invoice);
        dealerLedgerService.syncInvoiceLedger(invoice, null);

        if (arJournalEntryId != null) {
            slip.setJournalEntryId(arJournalEntryId);
        }
        if (cogsJournalId != null) {
            slip.setCogsJournalEntryId(cogsJournalId);
        }
        if (invoice.getId() != null) {
            slip.setInvoiceId(invoice.getId());
        }
        packagingSlipRepository.save(slip);

        if (arJournalEntryId != null && order.getSalesJournalEntryId() == null) {
            order.setSalesJournalEntryId(arJournalEntryId);
        }
        if (cogsJournalId != null && order.getCogsJournalEntryId() == null) {
            order.setCogsJournalEntryId(cogsJournalId);
        }
        if (invoice.getId() != null && order.getFulfillmentInvoiceId() == null) {
            order.setFulfillmentInvoiceId(invoice.getId());
        }
        String nextStatus = resolveOrderStatusAfterDispatch(company, order);
        if (!nextStatus.equalsIgnoreCase(order.getStatus())) {
            order.setStatus(nextStatus);
        }
        salesOrderRepository.save(order);

        return new DispatchConfirmResponse(
                slip.getId(),
                salesOrderId,
                invoice.getId(),
                arJournalEntryId,
                cogsDtos,
                true,
                arPostings
        );
    }

    private DispatchConfirmResponse.AccountPostingDto toPosting(Long accountId, String label, BigDecimal debit, BigDecimal credit) {
        if (accountId == null) {
            return new DispatchConfirmResponse.AccountPostingDto(null, label, debit, credit);
        }
        String name = accountRepository.findById(accountId)
                .map(Account::getName)
                .orElse("Account " + accountId);
        return new DispatchConfirmResponse.AccountPostingDto(accountId, name, debit, credit);
    }

    private static final class OrderItemAllocation {
        private final SalesOrderItem item;
        private BigDecimal remaining;

        private OrderItemAllocation(SalesOrderItem item, BigDecimal remaining) {
            this.item = item;
            this.remaining = remaining != null ? remaining : BigDecimal.ZERO;
        }
    }

    private String resolveOrderStatusAfterDispatch(Company company, SalesOrder order) {
        if (order == null || company == null) {
            return order != null ? order.getStatus() : null;
        }
        List<PackagingSlip> slips = packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, order.getId());
        if (slips.isEmpty()) {
            return order.getStatus();
        }
        boolean allDispatched = slips.stream().allMatch(slip -> "DISPATCHED".equalsIgnoreCase(slip.getStatus()));
        if (allDispatched) {
            return "SHIPPED";
        }
        boolean anyBackorder = slips.stream().anyMatch(slip -> "BACKORDER".equalsIgnoreCase(slip.getStatus()));
        if (anyBackorder) {
            return "PENDING_PRODUCTION";
        }
        return "READY_TO_SHIP";
    }

    private LocalDate currentDate(Company company) {
        return LocalDate.now(ZoneId.of(company.getTimezone()));
    }

}
