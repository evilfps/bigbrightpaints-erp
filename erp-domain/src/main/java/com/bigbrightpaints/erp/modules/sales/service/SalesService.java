package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
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
import com.bigbrightpaints.erp.modules.sales.domain.*;
import com.bigbrightpaints.erp.modules.sales.dto.*;
import com.bigbrightpaints.erp.modules.sales.event.SalesOrderCreatedEvent;
import jakarta.transaction.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.stream.Collectors;

@Service
public class SalesService {

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
                        InvoiceRepository invoiceRepository) {
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
        dealer.setName(request.name());
        dealer.setCode(request.code());
        dealer.setEmail(request.email());
        dealer.setPhone(request.phone());
        dealer.setCreditLimit(request.creditLimit());
        return toDto(dealer);
    }

    public void deleteDealer(Long id) {
        Dealer dealer = requireDealer(id);
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

    @Transactional
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
            dealer = requireDealer(request.dealerId());
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
        return salesOrderRepository.findWithItemsById(id)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));
    }

    private GstTreatment resolveGstTreatment(String value) {
        if (!StringUtils.hasText(value)) {
            return GstTreatment.NONE;
        }
        try {
            return GstTreatment.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown GST treatment " + value);
        }
    }

    private BigDecimal normalizePercent(BigDecimal rate) {
        if (rate == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal sanitized = rate.max(BigDecimal.ZERO);
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
            if (subtotal.compareTo(BigDecimal.ZERO) > 0 && targetTax.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal distributed = BigDecimal.ZERO;
                for (int i = 0; i < items.size(); i++) {
                    SalesOrderItem item = items.get(i);
                    BigDecimal share = targetTax.multiply(item.getLineSubtotal())
                            .divide(subtotal, 6, RoundingMode.HALF_UP);
                    share = currency(share);
                    if (i == items.size() - 1) {
                        BigDecimal remainder = targetTax.subtract(distributed.add(share));
                        share = share.add(remainder);
                        rounding = rounding.add(remainder);
                    }
                    distributed = distributed.add(share);
                    item.setGstRate(rate);
                    item.setGstAmount(share);
                    item.setLineTotal(currency(item.getLineSubtotal().add(share)));
                }
            } else {
                for (SalesOrderItem item : items) {
                    item.setGstRate(rate);
                    item.setGstAmount(BigDecimal.ZERO);
                    item.setLineTotal(item.getLineSubtotal());
                }
            }
            taxTotal = currency(subtotal.multiply(rate)
                    .divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
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

    /* Dispatch confirmation: inventory issue + COGS posting (AR/Invoice to be wired) */
    @Transactional
    public DispatchConfirmResponse confirmDispatch(DispatchConfirmRequest request) {
        PackagingSlip slip = null;
        Company company = companyContextService.requireCurrentCompany();
        if (request.packingSlipId() != null) {
            slip = packagingSlipRepository.findByIdAndCompany(request.packingSlipId(), company)
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Packing slip not found"));
        } else if (request.orderId() != null) {
            slip = packagingSlipRepository.findBySalesOrderId(request.orderId())
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                            "Packing slip not found for order " + request.orderId()));
        } else {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "packingSlipId or orderId is required");
        }
        Long salesOrderId = slip.getSalesOrder() != null ? slip.getSalesOrder().getId() : request.orderId();
        if (salesOrderId == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Sales order is required on packing slip");
        }
        Optional<Invoice> existingInvoiceOpt = invoiceRepository.findBySalesOrderId(salesOrderId);
        if ("DISPATCHED".equalsIgnoreCase(slip.getStatus())) {
            Long existingInvoiceId = existingInvoiceOpt.map(Invoice::getId).orElse(null);
            Long existingJeId = existingInvoiceOpt.flatMap(inv -> Optional.ofNullable(inv.getJournalEntry()))
                    .map(j -> j.getId())
                    .orElse(null);
            return new DispatchConfirmResponse(slip.getId(), salesOrderId, existingInvoiceId, existingJeId, List.of(), true);
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

        SalesOrder order = requireOrder(salesOrderId);
        Dealer dealer = order.getDealer();
        if (dealer == null || dealer.getReceivableAccount() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Dealer with receivable account is required for dispatch");
        }

        // Build shipped financial lines from slip lines + overrides
        Map<String, SalesOrderItem> orderItemsByProduct = order.getItems().stream()
                .collect(Collectors.toMap(SalesOrderItem::getProductCode, it -> it, (a, b) -> a));
        Map<Long, BigDecimal> revenueByAccount = new HashMap<>();
        Map<Long, BigDecimal> taxByAccount = new HashMap<>();
        List<InvoiceLine> invoiceLines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;

        for (var slipLine : slip.getLines()) {
            DispatchConfirmRequest.DispatchLine override = lineOverrides.get(slipLine.getId());
            BigDecimal requestedShip = override != null && override.shipQty() != null ? override.shipQty() : slipLine.getQuantity();
            if (requestedShip == null || requestedShip.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            FinishedGood fg = slipLine.getFinishedGoodBatch().getFinishedGood();
            SalesOrderItem item = orderItemsByProduct.get(fg.getProductCode());
            if (item == null) {
                continue;
            }
            BigDecimal onHand = fg.getCurrentStock() == null ? BigDecimal.ZERO : fg.getCurrentStock();
            BigDecimal shipQty = requestedShip.min(onHand);
            if (shipQty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Insufficient stock to dispatch " + fg.getProductCode());
            }
            BigDecimal price = override != null && override.priceOverride() != null ? override.priceOverride() : item.getUnitPrice();
            BigDecimal discount = override != null && override.discount() != null ? override.discount() : BigDecimal.ZERO;
            BigDecimal taxRate = override != null && override.taxRate() != null ? override.taxRate() : (item.getGstRate() == null ? BigDecimal.ZERO : item.getGstRate());

            BigDecimal lineGross = price.multiply(shipQty);
            BigDecimal lineNet = lineGross.subtract(discount);
            BigDecimal lineTax;
            if (Boolean.TRUE.equals(override != null ? override.taxInclusive() : Boolean.FALSE)) {
                BigDecimal divisor = BigDecimal.ONE.add(taxRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
                BigDecimal preTax = lineNet.divide(divisor, 6, RoundingMode.HALF_UP);
                lineTax = lineNet.subtract(preTax);
                lineNet = preTax;
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
            invLine.setQuantity(shipQty);
            invLine.setUnitPrice(price);
            invLine.setTaxRate(taxRate);
            invLine.setLineTotal(lineTotal);
            invoiceLines.add(invLine);
            slipLine.setQuantity(shipQty);
        }

        BigDecimal totalAmount = subtotal.add(taxTotal);
        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "No shippable quantity available for dispatch");
        }

        // Credit limit check
        if (dealer.getCreditLimit() != null && dealer.getCreditLimit().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal outstanding = dealerLedgerService.currentBalance(dealer.getId());
            if (outstanding.add(totalAmount).compareTo(dealer.getCreditLimit()) > 0
                    && !Boolean.TRUE.equals(request.adminOverrideCreditLimit())) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Credit limit exceeded for dealer " + dealer.getName());
            }
        }

        // Post inventory/COGS
        List<FinishedGoodsService.DispatchPosting> postings = finishedGoodsService.markSlipDispatched(salesOrderId);
        List<DispatchConfirmResponse.CogsPostingDto> cogsDtos = postings.stream()
                .map(p -> new DispatchConfirmResponse.CogsPostingDto(p.inventoryAccountId(), p.cogsAccountId(), p.cost()))
                .toList();

        // Post AR/Revenue/Tax journal
        // Post COGS/Inventory for dispatched quantities
        final PackagingSlip slipRef = slip;
        final String slipNumber = slipRef.getSlipNumber();
        final LocalDate dispatchedDate = slipRef.getDispatchedAt() != null
                ? LocalDate.ofInstant(slipRef.getDispatchedAt(), ZoneId.of(company.getTimezone()))
                : currentDate(company);

        Long cogsJournalId = null;
        if (postings != null && !postings.isEmpty()) {
            Map<String, BigDecimal> costByPair = new HashMap<>();
            Map<String, Long[]> accountsByPair = new HashMap<>();
            for (FinishedGoodsService.DispatchPosting posting : postings) {
                if (posting.cogsAccountId() == null || posting.inventoryAccountId() == null) {
                    continue;
                }
                String key = posting.cogsAccountId() + ":" + posting.inventoryAccountId();
                costByPair.merge(key, posting.cost(), BigDecimal::add);
                accountsByPair.putIfAbsent(key, new Long[]{posting.cogsAccountId(), posting.inventoryAccountId()});
            }
            BigDecimal totalCost = BigDecimal.ZERO;
            List<com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest.JournalLineRequest> cogsLines = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> entry : costByPair.entrySet()) {
                Long[] acc = accountsByPair.get(entry.getKey());
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
                String cogsRef = "COGS-DISP-" + slipRef.getId();
                Optional<JournalEntry> existingCogs = companyEntityLookup.findJournalEntryByReference(company, cogsRef);
                if (existingCogs.isPresent()) {
                    cogsJournalId = existingCogs.get().getId();
                } else {
                    var req = new com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest(
                            cogsRef,
                            dispatchedDate,
                            "COGS for dispatch " + slipNumber,
                            dealer.getId(),
                            null,
                            request.adminOverrideCreditLimit(),
                            cogsLines
                    );
                    cogsJournalId = accountingService.createJournalEntry(req).id();
                }
            }
        }

        Long arJournalEntryId = null;
        if (totalAmount.compareTo(BigDecimal.ZERO) > 0) {
            if (revenueByAccount.isEmpty()) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "No revenue or tax account configured for dispatched items");
            }
            String reference = "DISP-" + slipRef.getId();
            LocalDate dispatchDate = dispatchedDate;
            Optional<JournalEntry> existingAr = companyEntityLookup.findJournalEntryByReference(company, reference);
            if (existingAr.isPresent()) {
                arJournalEntryId = existingAr.get().getId();
            } else {
                List<com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest.JournalLineRequest> jeLines = new ArrayList<>();
                jeLines.add(new com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest.JournalLineRequest(
                        dealer.getReceivableAccount().getId(), "AR for dispatch " + slipNumber, totalAmount, BigDecimal.ZERO));
                for (var entry : revenueByAccount.entrySet()) {
                    jeLines.add(new com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest.JournalLineRequest(
                            entry.getKey(), "Revenue for dispatch " + slipNumber, BigDecimal.ZERO, entry.getValue()));
                }
                for (var entry : taxByAccount.entrySet()) {
                    jeLines.add(new com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest.JournalLineRequest(
                            entry.getKey(), "Tax for dispatch " + slipNumber, BigDecimal.ZERO, entry.getValue()));
                }
                var jeRequest = new com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest(
                        reference,
                        dispatchDate,
                        "Dispatch " + slipNumber,
                        dealer.getId(),
                        null,
                        request.adminOverrideCreditLimit(),
                        jeLines
                );
                arJournalEntryId = accountingService.createJournalEntry(jeRequest).id();
            }
        }

        // Create final invoice for shipped qty
        Invoice invoice = existingInvoiceOpt.orElseGet(Invoice::new);
        boolean newInvoice = invoice.getId() == null;
        if (newInvoice) {
            invoice.setCompany(company);
            invoice.setDealer(dealer);
            invoice.setSalesOrder(order);
            invoice.setInvoiceNumber(invoiceNumberService.nextInvoiceNumber(company));
            invoice.setCurrency(order.getCurrency());
            invoice.setIssueDate(currentDate(company));
            invoice.setDueDate(currentDate(company).plusDays(15));
            invoice.setStatus("ISSUED");
            invoice.setSubtotal(subtotal);
            invoice.setTaxTotal(taxTotal);
            invoice.setTotalAmount(totalAmount);
            for (InvoiceLine line : invoiceLines) {
                line.setInvoice(invoice);
                invoice.getLines().add(line);
            }
        }
        if (arJournalEntryId != null && (invoice.getJournalEntry() == null || !arJournalEntryId.equals(invoice.getJournalEntry().getId()))) {
            invoice.setJournalEntry(companyEntityLookup.requireJournalEntry(company, arJournalEntryId));
        }
        invoiceRepository.save(invoice);

        return new DispatchConfirmResponse(
                slip.getId(),
                salesOrderId,
                invoice.getId(),
                arJournalEntryId,
                cogsDtos,
                true
        );
    }

    private LocalDate currentDate(Company company) {
        return LocalDate.now(ZoneId.of(company.getTimezone()));
    }
}
