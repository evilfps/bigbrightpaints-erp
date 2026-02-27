package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.CreditLimitExceededException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
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
import com.bigbrightpaints.erp.modules.sales.util.DealerProvisioningSupport;
import com.bigbrightpaints.erp.modules.sales.util.SalesOrderReference;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SalesService {
    private static final Logger log = LoggerFactory.getLogger(SalesService.class);
    private static final BigDecimal MAX_GST_RATE = new BigDecimal("28.00");
    private static final BigDecimal DISPATCH_TOTAL_TOLERANCE = new BigDecimal("0.01");
    private static final Set<String> WORKFLOW_ONLY_STATUSES = Set.of(
            "BOOKED",
            "RESERVED",
            "PENDING_PRODUCTION",
            "PENDING_INVENTORY",
            "CONFIRMED",
            "CANCELLED",
            "SHIPPED",
            "FULFILLED",
            "COMPLETED"
    );
    private static final Set<String> MANUAL_STATUSES = Set.of(
            "ON_HOLD",
            "REJECTED",
            "CLOSED"
    );
    private static final Set<String> TERMINAL_MANUAL_STATUSES = Set.of(
            "REJECTED",
            "CLOSED"
    );
    private static final Set<String> ORCHESTRATOR_WORKFLOW_STATUSES = Set.of(
            "PROCESSING",
            "READY_TO_SHIP",
            "PENDING_PRODUCTION",
            "RESERVED"
    );
    private static final Set<String> CONFIRMABLE_ORDER_STATUSES = Set.of(
            "BOOKED",
            "RESERVED",
            "PENDING_PRODUCTION",
            "PENDING_INVENTORY"
    );
    private static final Set<String> VALID_ORDER_STATUSES = Set.of(
            "BOOKED",
            "RESERVED",
            "PENDING_PRODUCTION",
            "PENDING_INVENTORY",
            "CONFIRMED",
            "CANCELLED",
            "SHIPPED",
            "FULFILLED",
            "COMPLETED",
            "ON_HOLD",
            "REJECTED",
            "CLOSED"
    );
    private static final Set<String> VALID_CREDIT_REQUEST_STATUSES = Set.of(
            "PENDING",
            "APPROVED",
            "REJECTED"
    );
    private static final String CREDIT_REQUEST_STATUS_PENDING = "PENDING";
    private static final String DEFAULT_ORDER_PAYMENT_MODE = "CREDIT";
    private static final Set<String> VALID_ORDER_PAYMENT_MODES = Set.of("CASH", "CREDIT", "SPLIT");
    private static final Set<String> CREDIT_EXPOSURE_PAYMENT_MODES = Set.of("CASH", "CREDIT", "SPLIT");
    private static final String DISPATCH_REASON_CODE_CREDIT_LIMIT = "CREDIT_LIMIT_EXCEPTION";
    private static final String DISPATCH_REASON_CODE_PRICE_OVERRIDE = "PRICE_OVERRIDE";
    private static final String DISPATCH_REASON_CODE_DISCOUNT_OVERRIDE = "DISCOUNT_OVERRIDE";
    private static final String DISPATCH_REASON_CODE_TAX_OVERRIDE = "TAX_OVERRIDE";
    private static final String DISPATCH_REASON_CODE_LINE_OVERRIDE = "LINE_OVERRIDE";
    private static final String DISPATCH_REASON_CODE_COMPOSITE_OVERRIDE = "COMPOSITE_OVERRIDE";

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
    private final CompanyClock companyClock;
    private final PackagingSlipRepository packagingSlipRepository;
    private final FinishedGoodsService finishedGoodsService;
    private final AccountingService accountingService;
    private final AccountingFacade accountingFacade;
    private final JournalEntryRepository journalEntryRepository;
    private final InvoiceNumberService invoiceNumberService;
    private final InvoiceRepository invoiceRepository;
    private final FactoryTaskRepository factoryTaskRepository;
    private final CompanyDefaultAccountsService companyDefaultAccountsService;
    private final CompanyAccountingSettingsService companyAccountingSettingsService;
    private final CreditLimitOverrideService creditLimitOverrideService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

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
                        JournalEntryRepository journalEntryRepository,
                        InvoiceNumberService invoiceNumberService,
                        InvoiceRepository invoiceRepository,
                        FactoryTaskRepository factoryTaskRepository,
                        CompanyDefaultAccountsService companyDefaultAccountsService,
                        CompanyAccountingSettingsService companyAccountingSettingsService,
                        CreditLimitOverrideService creditLimitOverrideService,
                        AuditService auditService,
                        CompanyClock companyClock,
                        PlatformTransactionManager transactionManager) {
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
        this.journalEntryRepository = journalEntryRepository;
        this.invoiceNumberService = invoiceNumberService;
        this.invoiceRepository = invoiceRepository;
        this.factoryTaskRepository = factoryTaskRepository;
        this.companyDefaultAccountsService = companyDefaultAccountsService;
        this.companyAccountingSettingsService = companyAccountingSettingsService;
        this.creditLimitOverrideService = creditLimitOverrideService;
        this.auditService = auditService;
        this.companyClock = companyClock;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.transactionTemplate = template;
    }

    /* Dealers */
    public List<DealerDto> listDealers() {
        Company company = companyContextService.requireCurrentCompany();
        List<Dealer> dealers = dealerRepository.findByCompanyAndStatusIgnoreCaseOrderByNameAsc(
                company, DealerProvisioningSupport.ACTIVE_STATUS);
        List<Long> dealerIds = dealers.stream().map(Dealer::getId).toList();
        var balances = dealerLedgerService.currentBalances(dealerIds);
        return dealers.stream()
                .map(dealer -> toDto(dealer, balances.getOrDefault(dealer.getId(), BigDecimal.ZERO)))
                .toList();
    }

    @Transactional
    public DealerDto createDealer(DealerRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = dealerRepository.findByCompanyAndCodeIgnoreCase(company, request.code())
                .orElseGet(() -> {
                    Dealer fresh = new Dealer();
                    fresh.setCompany(company);
                    return fresh;
                });
        dealer.setName(request.name());
        dealer.setCode(request.code());
        dealer.setEmail(request.email());
        dealer.setPhone(request.phone());
        dealer.setCreditLimit(request.creditLimit());
        dealer.setStatus(DealerProvisioningSupport.ACTIVE_STATUS);
        if (dealer.getReceivableAccount() == null) {
            dealer.setReceivableAccount(DealerProvisioningSupport.createReceivableAccount(company, dealer, accountRepository));
        } else if (!dealer.getReceivableAccount().isActive()) {
            Account receivableAccount = dealer.getReceivableAccount();
            receivableAccount.setActive(true);
            accountRepository.save(receivableAccount);
        }
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
        dealer.setStatus(DealerProvisioningSupport.INACTIVE_STATUS);
        Account receivableAccount = dealer.getReceivableAccount();
        if (receivableAccount != null) {
            receivableAccount.setActive(false);
            accountRepository.save(receivableAccount);
        }
        dealerRepository.save(dealer);
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

    /* Sales Orders */
    public List<SalesOrderDto> listOrders(String status, int page, int size) {
        return listOrders(status, null, page, size);
    }

    public List<SalesOrderDto> listOrders(String status, Long dealerId, int page, int size) {
        Company company = companyContextService.requireCurrentCompany();
        int safeSize = Math.max(1, Math.min(size, 200));
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize);
        Dealer dealer = dealerId != null ? requireDealer(dealerId) : null;
        Page<Long> orderIds;
        if (status == null || status.isBlank()) {
            orderIds = dealer == null
                    ? salesOrderRepository.findIdsByCompanyOrderByCreatedAtDescIdDesc(company, pageable)
                    : salesOrderRepository.findIdsByCompanyAndDealerOrderByCreatedAtDescIdDesc(company, dealer, pageable);
        } else {
            orderIds = dealer == null
                    ? salesOrderRepository.findIdsByCompanyAndStatusOrderByCreatedAtDescIdDesc(company, status, pageable)
                    : salesOrderRepository.findIdsByCompanyAndDealerAndStatusOrderByCreatedAtDescIdDesc(
                            company, dealer, status, pageable);
        }
        List<Long> ids = orderIds.getContent();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<SalesOrder> orders = salesOrderRepository.findByCompanyAndIdInOrderByCreatedAtDescIdDesc(company, ids);
        return orders.stream().map(this::toDto).toList();
    }

    public List<SalesOrderDto> listOrders(String status) {
        return listOrders(status, null);
    }

    public List<SalesOrderDto> listOrders(String status, Long dealerId) {
        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = dealerId != null ? requireDealer(dealerId) : null;
        List<SalesOrder> orders;
        if (status == null || status.isBlank()) {
            orders = dealer == null
                    ? salesOrderRepository.findByCompanyOrderByCreatedAtDesc(company)
                    : salesOrderRepository.findByCompanyAndDealerOrderByCreatedAtDesc(company, dealer);
        } else {
            orders = dealer == null
                    ? salesOrderRepository.findByCompanyAndStatusOrderByCreatedAtDesc(company, status)
                    : salesOrderRepository.findByCompanyAndDealerAndStatusOrderByCreatedAtDesc(
                            company, dealer, status);
        }
        return orders.stream().map(this::toDto).toList();
    }

    public SalesOrderDto createOrder(SalesOrderRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        String idempotencyKey = request.resolveIdempotencyKey();
        String legacyDefaultPaymentIdempotencyKey = resolveLegacyDefaultPaymentIdempotencyKey(request, idempotencyKey);
        String requestSignature = buildSalesOrderSignature(request);
        String legacyDefaultPaymentRequestSignature = buildSalesOrderSignatureIncludingDefaultPaymentMode(request);
        if (legacyDefaultPaymentIdempotencyKey != null) {
            Optional<SalesOrderDto> legacyDefaultMatch = resolveOrderByIdempotencyKey(
                    company,
                    legacyDefaultPaymentIdempotencyKey,
                    requestSignature,
                    legacyDefaultPaymentRequestSignature
            );
            if (legacyDefaultMatch.isPresent()) {
                return legacyDefaultMatch.get();
            }
        }
        try {
            SalesOrderDto created = transactionTemplate.execute(status ->
                    createOrderInternal(
                            company,
                            request,
                            idempotencyKey,
                            requestSignature,
                            legacyDefaultPaymentIdempotencyKey,
                            legacyDefaultPaymentRequestSignature
                    ));
            if (created == null) {
                throw new IllegalStateException("Failed to create sales order for " + idempotencyKey);
            }
            return created;
        } catch (DataIntegrityViolationException ex) {
            SalesOrderDto resolved = transactionTemplate.execute(status ->
                    resolveIdempotentOrderInternal(
                            company,
                            idempotencyKey,
                            requestSignature,
                            legacyDefaultPaymentRequestSignature,
                            ex
                    ));
            if (resolved == null) {
                throw ex;
            }
            return resolved;
        }
    }

    private SalesOrderDto createOrderInternal(Company company,
                                              SalesOrderRequest request,
                                              String idempotencyKey,
                                              String requestSignature,
                                              String legacyDefaultPaymentIdempotencyKey,
                                              String legacyDefaultPaymentRequestSignature) {
        Optional<SalesOrder> existing = salesOrderRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey);
        if (existing.isPresent()) {
            return resolveExistingOrder(
                    existing.get(),
                    idempotencyKey,
                    requestSignature,
                    buildSalesOrderSignatureIncludingDefaultPaymentMode(request)
            );
        }
        GstTreatment gstTreatment = resolveGstTreatment(request.gstTreatment());
        BigDecimal orderLevelRate = resolveOrderLevelRate(company, gstTreatment, request.gstRate());
        String paymentMode = normalizeOrderPaymentMode(request.paymentMode());
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
        order.setIdempotencyHash(requestSignature);
        order.setGstInclusive(gstInclusive);
        OrderAmountSummary amounts = mapOrderItems(order, items, gstTreatment, orderLevelRate, gstInclusive);
        validateTotalAmount(request.totalAmount(), amounts.total());
        if (requiresCreditLimitCheck(paymentMode)) {
            try {
                enforceCreditLimit(company, order.getDealer(), amounts.total());
            } catch (CreditLimitExceededException | IllegalStateException ex) {
                Optional<SalesOrderDto> replay = resolveCreateOrderReplayAfterCreditFailure(
                        company,
                        idempotencyKey,
                        legacyDefaultPaymentIdempotencyKey,
                        requestSignature,
                        legacyDefaultPaymentRequestSignature
                );
                if (replay.isPresent()) {
                    return replay.get();
                }
                throw ex;
            }
        }
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

    private Optional<SalesOrderDto> resolveCreateOrderReplayAfterCreditFailure(Company company,
                                                                                String idempotencyKey,
                                                                                String legacyDefaultPaymentIdempotencyKey,
                                                                                String requestSignature,
                                                                                String legacyDefaultPaymentRequestSignature) {
        Optional<SalesOrderDto> canonicalReplay = resolveOrderByIdempotencyKey(
                company,
                idempotencyKey,
                requestSignature,
                legacyDefaultPaymentRequestSignature
        );
        if (canonicalReplay.isPresent()) {
            return canonicalReplay;
        }
        if (!StringUtils.hasText(legacyDefaultPaymentIdempotencyKey)) {
            return Optional.empty();
        }
        return resolveOrderByIdempotencyKey(
                company,
                legacyDefaultPaymentIdempotencyKey,
                requestSignature,
                legacyDefaultPaymentRequestSignature
        );
    }

    private SalesOrderDto resolveIdempotentOrderInternal(Company company,
                                                         String idempotencyKey,
                                                         String requestSignature,
                                                         String legacyDefaultPaymentRequestSignature,
                                                         DataIntegrityViolationException rootCause) {
        Optional<SalesOrder> existing = salesOrderRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey);
        if (existing.isEmpty()) {
            throw rootCause;
        }
        return resolveExistingOrder(existing.get(), idempotencyKey, requestSignature, legacyDefaultPaymentRequestSignature);
    }

    private Optional<SalesOrderDto> resolveOrderByIdempotencyKey(Company company,
                                                                 String idempotencyKey,
                                                                 String requestSignature,
                                                                 String legacyDefaultPaymentRequestSignature) {
        return salesOrderRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey)
                .map(order -> resolveExistingOrder(
                        order,
                        idempotencyKey,
                        requestSignature,
                        legacyDefaultPaymentRequestSignature
                ));
    }

    private SalesOrderDto resolveExistingOrder(SalesOrder order,
                                               String idempotencyKey,
                                               String requestSignature,
                                               String legacyDefaultPaymentRequestSignature) {
        String storedSignature = order.getIdempotencyHash();
        if (!StringUtils.hasText(storedSignature)) {
            String derivedSignature = buildSalesOrderSignature(order);
            String legacyDefaultPaymentDerivedSignature = buildSalesOrderSignatureIncludingDefaultPaymentMode(order);
            if (!matchesSignature(derivedSignature, requestSignature, legacyDefaultPaymentRequestSignature)
                    && !matchesSignature(legacyDefaultPaymentDerivedSignature, requestSignature, legacyDefaultPaymentRequestSignature)) {
                throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                        "Idempotency key already used with different payload")
                        .withDetail("idempotencyKey", idempotencyKey);
            }
            order.setIdempotencyHash(requestSignature);
            salesOrderRepository.save(order);
            return toDto(order);
        }
        if (!matchesSignature(storedSignature, requestSignature, legacyDefaultPaymentRequestSignature)) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Idempotency key already used with different payload")
                    .withDetail("idempotencyKey", idempotencyKey);
        }
        if (!storedSignature.equals(requestSignature)) {
            order.setIdempotencyHash(requestSignature);
            salesOrderRepository.save(order);
        }
        return toDto(order);
    }

    private boolean matchesSignature(String candidate,
                                     String requestSignature,
                                     String legacyDefaultPaymentRequestSignature) {
        return candidate.equals(requestSignature) || candidate.equals(legacyDefaultPaymentRequestSignature);
    }

    private String buildSalesOrderSignature(SalesOrderRequest request) {
        return buildSalesOrderSignature(request, false);
    }

    private String buildSalesOrderSignatureIncludingDefaultPaymentMode(SalesOrderRequest request) {
        return buildSalesOrderSignature(request, true);
    }

    private String buildSalesOrderSignature(SalesOrderRequest request, boolean includeDefaultPaymentModeToken) {
        StringBuilder signature = new StringBuilder();
        signature.append(request.dealerId() == null ? "null" : request.dealerId())
                .append('|').append(amountToken(request.totalAmount()))
                .append('|').append(normalizeText(request.currency()))
                .append('|').append(normalizeText(request.gstTreatment()))
                .append('|').append(Boolean.TRUE.equals(request.gstInclusive()))
                .append('|').append(amountToken(request.gstRate()))
                .append('|').append(normalizeText(request.notes()));
        appendPaymentModeSignatureToken(
                signature,
                normalizeOrderPaymentMode(request.paymentMode()),
                includeDefaultPaymentModeToken
        );
        request.items().stream()
                .sorted(orderRequestComparator())
                .forEach(item -> signature.append('|')
                        .append(normalizeText(item.productCode()))
                        .append(':').append(amountToken(item.quantity()))
                        .append(':').append(amountToken(item.unitPrice()))
                        .append(':').append(amountToken(item.gstRate())));
        return DigestUtils.sha256Hex(signature.toString());
    }

    private String buildSalesOrderSignature(SalesOrder order) {
        return buildSalesOrderSignature(order, false);
    }

    private String buildSalesOrderSignatureIncludingDefaultPaymentMode(SalesOrder order) {
        return buildSalesOrderSignature(order, true);
    }

    private String buildSalesOrderSignature(SalesOrder order, boolean includeDefaultPaymentModeToken) {
        StringBuilder signature = new StringBuilder();
        signature.append(order.getDealer() != null ? order.getDealer().getId() : "null")
                .append('|').append(amountToken(order.getTotalAmount()))
                .append('|').append(normalizeText(order.getCurrency()))
                .append('|').append(normalizeText(order.getGstTreatment()))
                .append('|').append(order.isGstInclusive())
                .append('|').append(amountToken(order.getGstRate()))
                .append('|').append(normalizeText(order.getNotes()));
        appendPaymentModeSignatureToken(
                signature,
                DEFAULT_ORDER_PAYMENT_MODE,
                includeDefaultPaymentModeToken
        );
        order.getItems().stream()
                .sorted(orderItemComparator())
                .forEach(item -> signature.append('|')
                        .append(normalizeText(item.getProductCode()))
                        .append(':').append(amountToken(item.getQuantity()))
                        .append(':').append(amountToken(item.getUnitPrice()))
                        .append(':').append(amountToken(item.getGstRate())));
        return DigestUtils.sha256Hex(signature.toString());
    }

    private Comparator<SalesOrderItemRequest> orderRequestComparator() {
        return Comparator.comparing((SalesOrderItemRequest item) -> normalizeText(item.productCode()))
                .thenComparing(item -> safeAmount(item.quantity()))
                .thenComparing(item -> safeAmount(item.unitPrice()));
    }

    private Comparator<SalesOrderItem> orderItemComparator() {
        return Comparator.comparing((SalesOrderItem item) -> normalizeText(item.getProductCode()))
                .thenComparing(item -> safeAmount(item.getQuantity()))
                .thenComparing(item -> safeAmount(item.getUnitPrice()));
    }

    private BigDecimal safeAmount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String amountToken(BigDecimal value) {
        return safeAmount(value).stripTrailingZeros().toPlainString();
    }

    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toUpperCase();
    }

    @Transactional
    public SalesOrderDto updateOrder(Long id, SalesOrderRequest request) {
        SalesOrder order = requireOrder(id);
        assertOrderMutable(order, "update");
        GstTreatment gstTreatment = resolveGstTreatment(request.gstTreatment());
        BigDecimal orderLevelRate = resolveOrderLevelRate(order.getCompany(), gstTreatment, request.gstRate());
        String paymentMode = normalizeOrderPaymentMode(request.paymentMode());
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
        String currency = StringUtils.hasText(request.currency()) ? request.currency() : order.getCurrency();
        if (!StringUtils.hasText(currency)) {
            currency = "INR";
        }
        order.setCurrency(currency);
        order.setNotes(request.notes());
        order.setGstInclusive(gstInclusive);
        OrderAmountSummary amounts = mapOrderItems(order, items, gstTreatment, orderLevelRate, gstInclusive);
        validateTotalAmount(request.totalAmount(), amounts.total());
        if (requiresCreditLimitCheck(paymentMode)) {
            enforceCreditLimit(order.getCompany(), order.getDealer(), amounts.total());
        }
        salesOrderRepository.save(order);
        FinishedGoodsService.InventoryReservationResult reservationResult = finishedGoodsService.reserveForOrder(order);
        if (reservationResult != null) {
            boolean noShortages = reservationResult.shortages() == null || reservationResult.shortages().isEmpty();
            order.setStatus(noShortages ? "RESERVED" : "PENDING_PRODUCTION");
            if (noShortages) {
                cancelPendingFactoryTasksForOrder(order);
            } else {
                Long packagingSlipId = reservationResult.packagingSlip() != null
                        ? reservationResult.packagingSlip().id()
                        : null;
                cancelPendingFactoryTasksForOrder(order);
                createShortageTasksForOrder(order.getCompany(), order, reservationResult.shortages(), packagingSlipId);
            }
        }
        return toDto(order);
    }

    public void deleteOrder(Long id) {
        SalesOrder order = requireOrder(id);
        assertOrderMutable(order, "delete");
        finishedGoodsService.releaseReservationsForOrder(order.getId());
        cancelFactoryTasksForOrder(order);
        salesOrderRepository.delete(order);
    }

    @Transactional
    public SalesOrderDto confirmOrder(Long id) {
        SalesOrder order = requireOrder(id);
        String currentStatus = normalizeStatusToken(order.getStatus());
        if ("CONFIRMED".equals(currentStatus)) {
            return toDto(order);
        }
        if (!CONFIRMABLE_ORDER_STATUSES.contains(currentStatus)) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Cannot confirm order with status " + currentStatus)
                    .withDetail("status", currentStatus)
                    .withDetail("allowedStatuses", CONFIRMABLE_ORDER_STATUSES);
        }
        assertOrderMutable(order, "confirm");
        order.setStatus("CONFIRMED");
        return toDto(order);
    }

    @Transactional
    public SalesOrderDto cancelOrder(Long id, String reason) {
        SalesOrder order = requireOrder(id);
        String currentStatus = order.getStatus();
        if ("CANCELLED".equalsIgnoreCase(currentStatus)) {
            return toDto(order);
        }
        assertOrderMutable(order, "cancel");

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
        String normalized = normalizeOrderStatus(status);
        if (!VALID_ORDER_STATUSES.contains(normalized)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Unknown order status " + normalized);
        }
        if (WORKFLOW_ONLY_STATUSES.contains(normalized)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Status " + normalized + " cannot be set directly; use the workflow endpoints");
        }
        if (!MANUAL_STATUSES.contains(normalized)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Status " + normalized + " cannot be set directly");
        }
        assertOrderMutable(order, "update status");
        if (normalized.equalsIgnoreCase(order.getStatus())) {
            return toDto(order);
        }
        order.setStatus(normalized);
        return toDto(order);
    }

    @Transactional
    public SalesOrderDto updateStatusInternal(Long id, String status) {
        SalesOrder order = requireOrder(id);
        order.setStatus(status);
        return toDto(order);
    }

    @Transactional
    public void updateOrchestratorWorkflowStatus(Long id, String status) {
        SalesOrder order = requireOrder(id);
        String normalized = normalizeOrchestratorStatus(status);
        if (!ORCHESTRATOR_WORKFLOW_STATUSES.contains(normalized)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Status " + normalized + " cannot be set by orchestrator");
        }
        String currentStatus = normalizeStatusToken(order.getStatus());
        if (TERMINAL_MANUAL_STATUSES.contains(currentStatus)) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Cannot update fulfillment order with status " + currentStatus);
        }
        assertOrderMutable(order, "update fulfillment");
        if (normalized.equalsIgnoreCase(order.getStatus())) {
            return;
        }
        order.setStatus(normalized);
    }

    @Transactional(readOnly = true)
    public boolean hasDispatchConfirmation(Long id) {
        SalesOrder order = requireOrder(id);
        Company company = companyContextService.requireCurrentCompany();
        List<PackagingSlip> slips = packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, order.getId());
        if (slips == null || slips.isEmpty()) {
            return false;
        }
        return slips.stream().anyMatch(this::hasDispatchTruth);
    }

    @Transactional
    public void attachTraceId(Long id, String traceId) {
        SalesOrder order = requireOrder(id);
        String existingTraceId = normalizeTraceId(order.getTraceId());
        String incomingTraceId = normalizeTraceId(traceId);
        if (!StringUtils.hasText(existingTraceId)) {
            if (StringUtils.hasText(incomingTraceId)) {
                order.setTraceId(incomingTraceId);
            }
            return;
        }
        if (!StringUtils.hasText(incomingTraceId)) {
            return;
        }
        if (existingTraceId.equals(incomingTraceId)) {
            return;
        }
        log.warn("Ignoring conflicting trace attach for sales order {}: existingTraceId={}, incomingTraceId={}",
                order.getId(), existingTraceId, incomingTraceId);
    }

    private SalesOrder requireOrder(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        return companyEntityLookup.requireSalesOrder(company, id);
    }

    private SalesOrder requireOrderForUpdate(Company company, Long id) {
        Optional<SalesOrder> lockedOrder = salesOrderRepository.findWithItemsByCompanyAndIdForUpdate(company, id);
        if (lockedOrder != null && lockedOrder.isPresent()) {
            return lockedOrder.get();
        }
        return companyEntityLookup.requireSalesOrder(company, id);
    }

    private List<PackagingSlip> findOrderSlips(Company company, Long orderId, boolean forUpdate) {
        if (company == null || orderId == null) {
            return List.of();
        }
        List<PackagingSlip> slips = null;
        if (forUpdate) {
            slips = packagingSlipRepository.findAllByCompanyAndSalesOrderIdForUpdate(company, orderId);
        }
        if (slips == null || slips.isEmpty()) {
            slips = packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId);
        }
        return slips != null ? slips : List.of();
    }

    private long activeSlipCount(List<PackagingSlip> slips) {
        if (slips == null || slips.isEmpty()) {
            return 0;
        }
        return slips.stream()
                .filter(slip -> !("CANCELLED".equalsIgnoreCase(slip.getStatus())))
                .count();
    }

    private boolean hasSingleActiveSlip(List<PackagingSlip> slips) {
        return activeSlipCount(slips) == 1;
    }

    private PackagingSlip findSingleActiveSlip(List<PackagingSlip> slips) {
        if (!hasSingleActiveSlip(slips)) {
            return null;
        }
        return slips.stream()
                .filter(slip -> !("CANCELLED".equalsIgnoreCase(slip.getStatus())))
                .findFirst()
                .orElse(null);
    }

    private void assertOrderMutable(SalesOrder order, String action) {
        if (order == null) {
            return;
        }
        String status = order.getStatus();
        if ("CANCELLED".equalsIgnoreCase(status) ||
            "SHIPPED".equalsIgnoreCase(status) ||
            "FULFILLED".equalsIgnoreCase(status) ||
            "COMPLETED".equalsIgnoreCase(status)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Cannot " + action + " order with status " + status);
        }
        if (order.hasInvoiceIssued() || order.hasSalesJournalPosted() || order.hasCogsJournalPosted()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Cannot " + action + " order with posted accounting entries");
        }
        Company company = order.getCompany();
        if (company != null) {
            boolean dispatchedSlip = packagingSlipRepository
                    .findAllByCompanyAndSalesOrderId(company, order.getId()).stream()
                    .anyMatch(slip -> "DISPATCHED".equalsIgnoreCase(slip.getStatus()));
            if (dispatchedSlip) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Cannot " + action + " order with dispatched slips");
            }
        }
    }

    private String normalizeOrderStatus(String status) {
        if (!StringUtils.hasText(status)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Status is required");
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeOrchestratorStatus(String status) {
        if (!StringUtils.hasText(status)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Status is required");
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private boolean hasDispatchTruth(PackagingSlip slip) {
        if (slip == null || !"DISPATCHED".equalsIgnoreCase(slip.getStatus())) {
            return false;
        }
        boolean hasInvoice = slip.getInvoiceId() != null;
        boolean hasArJournal = slip.getJournalEntryId() != null;
        boolean hasCogsJournal = slip.getCogsJournalEntryId() != null
                || (StringUtils.hasText(slip.getSlipNumber()) && accountingFacade.hasCogsJournalFor(slip.getSlipNumber()));
        return hasInvoice && hasArJournal && hasCogsJournal;
    }

    public SalesOrder getOrderWithItems(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        return salesOrderRepository.findWithItemsByCompanyAndId(company, id)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));
    }

    @Transactional
    public DispatchMarkerReconciliationResponse reconcileStaleOrderLevelMarkers(int limit) {
        Company company = companyContextService.requireCurrentCompany();
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<Long> reconciledOrderIds = new ArrayList<>();
        int scannedOrders = 0;
        int pageSize = Math.max(1, Math.min(safeLimit, 200));
        int pageNumber = 0;
        while (reconciledOrderIds.size() < safeLimit) {
            Page<Long> candidates = salesOrderRepository.findDispatchMarkerCandidateIdsByCompanyOrderByCreatedAtDescIdDesc(
                    company,
                    PageRequest.of(pageNumber, pageSize));
            if (candidates.isEmpty()) {
                break;
            }
            for (Long orderId : candidates.getContent()) {
                scannedOrders++;
                Optional<SalesOrder> orderOpt = salesOrderRepository.findWithItemsByCompanyAndIdForUpdate(company, orderId);
                if (orderOpt.isEmpty()) {
                    continue;
                }
                SalesOrder order = orderOpt.get();
                List<PackagingSlip> orderSlips = findOrderSlips(company, orderId, true);
                PackagingSlip singleActiveSlip = findSingleActiveSlip(orderSlips);
                boolean singleActiveSlipForOrder = singleActiveSlip != null;

                Long invoiceId = null;
                Long salesJournalEntryId = null;
                Long cogsJournalEntryId = null;
                if (singleActiveSlipForOrder) {
                    invoiceId = singleActiveSlip.getInvoiceId();
                    salesJournalEntryId = singleActiveSlip.getJournalEntryId();
                    cogsJournalEntryId = singleActiveSlip.getCogsJournalEntryId();
                    if (cogsJournalEntryId == null) {
                        String slipNumber = singleActiveSlip.getSlipNumber();
                        String cogsReferenceId = resolveCogsReferenceId(singleActiveSlip, slipNumber);
                        cogsJournalEntryId = findCogsJournalId(company, cogsReferenceId);
                        if (cogsJournalEntryId == null
                                && StringUtils.hasText(slipNumber)
                                && !slipNumber.trim().equalsIgnoreCase(cogsReferenceId)) {
                            cogsJournalEntryId = findCogsJournalId(company, slipNumber.trim());
                        }
                    }
                    if (cogsJournalEntryId == null) {
                        cogsJournalEntryId = resolveCogsMarkerForReconciliation(
                                company,
                                order,
                                singleActiveSlip,
                                null);
                    }
                    if (invoiceId == null && salesJournalEntryId == null && cogsJournalEntryId == null) {
                        // Avoid destructive cleanup when no slip-level anchors are available yet.
                        continue;
                    }
                }

                boolean updated = reconcileOrderLevelDispatchMarkers(
                        order,
                        invoiceId,
                        salesJournalEntryId,
                        cogsJournalEntryId,
                        singleActiveSlipForOrder);
                if (updated) {
                    salesOrderRepository.save(order);
                    reconciledOrderIds.add(orderId);
                    if (reconciledOrderIds.size() >= safeLimit) {
                        break;
                    }
                }
            }
            if (!candidates.hasNext()) {
                break;
            }
            pageNumber++;
        }
        return new DispatchMarkerReconciliationResponse(
                scannedOrders,
                reconciledOrderIds.size(),
                List.copyOf(reconciledOrderIds));
    }

    private Long resolveCogsMarkerForReconciliation(Company company,
                                                    SalesOrder order,
                                                    PackagingSlip slip,
                                                    Long resolvedCogsJournalId) {
        Long cogsJournalEntryId = resolvedCogsJournalId;
        if (cogsJournalEntryId != null || slip == null) {
            return cogsJournalEntryId;
        }
        String slipNumber = slip.getSlipNumber();
        String cogsReferenceId = resolveCogsReferenceId(slip, slipNumber);
        boolean hasCogsJournal = accountingFacade.hasCogsJournalFor(cogsReferenceId)
                || (StringUtils.hasText(slipNumber)
                && !slipNumber.trim().equalsIgnoreCase(cogsReferenceId)
                && accountingFacade.hasCogsJournalFor(slipNumber.trim()));
        if (hasCogsJournal) {
            return order != null ? order.getCogsJournalEntryId() : null;
        }
        return null;
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
        return MoneyUtils.roundCurrency(value);
    }

    private PackagingSlip selectMostRecentSlip(List<PackagingSlip> slips, Long orderId) {
        Comparator<PackagingSlip> byCreatedAt = Comparator.comparing(
                PackagingSlip::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder()));
        Comparator<PackagingSlip> byId = Comparator.comparing(
                PackagingSlip::getId,
                Comparator.nullsLast(Comparator.naturalOrder()));
        PackagingSlip selected = slips.stream()
                .max(byCreatedAt.thenComparing(byId))
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Packing slip not found for order " + orderId));
        log.warn("Multiple packing slips found for order {}; using slip {}", orderId, selected.getId());
        return selected;
    }

    private SalesOrderDto toDto(SalesOrder order) {
        String dealerName = order.getDealer() != null ? order.getDealer().getName() : null;
        List<SalesOrderItemDto> items = order.getItems().stream()
                .map(this::toItemDto)
                .collect(Collectors.toList());
        return new SalesOrderDto(order.getId(), order.getPublicId(), order.getOrderNumber(), order.getStatus(),
                order.getTotalAmount(), order.getSubtotalAmount(), order.getGstTotal(), order.getGstRate(),
                order.getGstTreatment(), order.isGstInclusive(), order.getGstRoundingAdjustment(), order.getCurrency(), dealerName,
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
                if (gstInclusive && !items.isEmpty()) {
                    BigDecimal lineSubtotalSum = items.stream()
                            .map(SalesOrderItem::getLineSubtotal)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal subtotalDelta = subtotal.subtract(lineSubtotalSum);
                    if (subtotalDelta.compareTo(BigDecimal.ZERO) != 0) {
                        SalesOrderItem lastItem = items.get(items.size() - 1);
                        BigDecimal adjustedSubtotal = currency(lastItem.getLineSubtotal().add(subtotalDelta));
                        lastItem.setLineSubtotal(adjustedSubtotal);
                        lastItem.setLineTotal(currency(adjustedSubtotal.add(lastItem.getGstAmount())));
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

    private BigDecimal resolveMinAllowedPriceForSku(Company company, String sku) {
        if (!StringUtils.hasText(sku)) {
            return BigDecimal.ZERO;
        }
        ProductionProduct product = productionProductRepository.findByCompanyAndSkuCode(company, sku)
                .orElseThrow(() -> new IllegalArgumentException("SKU " + sku + " not found"));
        return resolveMinAllowedPrice(product);
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

    private void enforceCreditLimit(Company company, Dealer dealer, BigDecimal orderTotal) {
        if (dealer == null || dealer.getId() == null) {
            return;
        }
        Dealer lockedDealer = dealerRepository.lockByCompanyAndId(company, dealer.getId())
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
            BigDecimal requiredHeadroom = projected.subtract(limit);
            if (requiredHeadroom.compareTo(BigDecimal.ZERO) < 0) {
                requiredHeadroom = BigDecimal.ZERO;
            }
            CreditLimitExceededException ex = new CreditLimitExceededException(String.format(
                    "Dealer %s credit limit exceeded. Limit %.2f, outstanding %.2f, attempted order %.2f",
                    lockedDealer.getName(),
                    limit,
                    outstanding,
                    total));
            ex.withDetail("dealerId", lockedDealer.getId())
                    .withDetail("companyId", company.getId())
                    .withDetail("currentExposure", outstanding)
                    .withDetail("creditLimit", limit)
                    .withDetail("orderAmount", total)
                    .withDetail("requiredHeadroom", requiredHeadroom);
            throw ex;
        }
    }

    private String normalizeOrderPaymentMode(String rawMode) {
        String normalized = StringUtils.hasText(rawMode)
                ? rawMode.trim().toUpperCase(Locale.ROOT)
                : DEFAULT_ORDER_PAYMENT_MODE;
        if (!VALID_ORDER_PAYMENT_MODES.contains(normalized)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Unsupported sales order payment mode: " + normalized)
                    .withDetail("paymentMode", normalized)
                    .withDetail("allowedPaymentModes", VALID_ORDER_PAYMENT_MODES);
        }
        return normalized;
    }

    private String resolveLegacyDefaultPaymentIdempotencyKey(SalesOrderRequest request, String canonicalIdempotencyKey) {
        if (StringUtils.hasText(request.idempotencyKey())) {
            return null;
        }
        String normalizedPaymentMode = normalizeOrderPaymentMode(request.paymentMode());
        if (!DEFAULT_ORDER_PAYMENT_MODE.equals(normalizedPaymentMode)) {
            return null;
        }
        String legacyDefaultPaymentIdempotencyKey = request.resolveIdempotencyKeyIncludingDefaultPaymentMode();
        if (legacyDefaultPaymentIdempotencyKey.equals(canonicalIdempotencyKey)) {
            return null;
        }
        return legacyDefaultPaymentIdempotencyKey;
    }

    private boolean requiresCreditLimitCheck(String paymentMode) {
        // Sales orders are fulfilled against receivable exposure; cash collection is settled
        // through accounting receipts later, so order booking must always enforce credit policy.
        return CREDIT_EXPOSURE_PAYMENT_MODES.contains(paymentMode);
    }

    private void appendPaymentModeSignatureToken(StringBuilder signature,
                                                 String normalizedPaymentMode,
                                                 boolean includeDefaultPaymentModeToken) {
        if (includeDefaultPaymentModeToken || !DEFAULT_ORDER_PAYMENT_MODE.equals(normalizedPaymentMode)) {
            signature.append('|').append(normalizedPaymentMode);
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
        LocalDate today = companyClock.today(company);
        for (FinishedGoodsService.InventoryShortage shortage : shortages) {
            BigDecimal shortageQty = shortage.shortageQuantity();

            // Determine urgency based on shortage quantity
            String urgencyPrefix;
            LocalDate dueDate;
            if (shortageQty.compareTo(new BigDecimal("100")) >= 0) {
                urgencyPrefix = "[URGENT] ";
                dueDate = today.plusDays(1); // Large shortage - due tomorrow
            } else if (shortageQty.compareTo(new BigDecimal("50")) >= 0) {
                urgencyPrefix = "[HIGH] ";
                dueDate = today.plusDays(3); // Medium shortage - 3 days
            } else {
                urgencyPrefix = "";
                dueDate = today.plusDays(7); // Small shortage - 1 week
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

    private void cancelPendingFactoryTasksForOrder(SalesOrder order) {
        if (order == null || order.getId() == null) {
            return;
        }
        List<FactoryTask> tasks = factoryTaskRepository.findByCompanyAndSalesOrderId(order.getCompany(), order.getId());
        if (tasks.isEmpty()) {
            return;
        }
        boolean updated = false;
        for (FactoryTask task : tasks) {
            String status = task.getStatus();
            if (status == null || "PENDING".equalsIgnoreCase(status)) {
                task.setStatus("CANCELLED");
                updated = true;
            }
        }
        if (updated) {
            factoryTaskRepository.saveAll(tasks);
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
        requireSalesTargetAdminAuthority();
        String actor = currentActorIdentity();
        String assignee = normalizeAssignee(request.assignee());
        assertNoSelfTargetAction(actor, assignee);
        String changeReason = requireChangeReason(request.changeReason(), "create");

        SalesTarget target = new SalesTarget();
        target.setCompany(companyContextService.requireCurrentCompany());
        mapTarget(target, request, assignee);
        SalesTarget saved = salesTargetRepository.save(target);
        auditSalesTargetMutation(AuditEvent.DATA_CREATE, saved, actor, changeReason);
        return toDto(saved);
    }

    @Transactional
    public SalesTargetDto updateTarget(Long id, SalesTargetRequest request) {
        requireSalesTargetAdminAuthority();
        String actor = currentActorIdentity();
        String assignee = normalizeAssignee(request.assignee());
        assertNoSelfTargetAction(actor, assignee);
        String changeReason = requireChangeReason(request.changeReason(), "update");

        SalesTarget target = requireTarget(id);
        assertNoSelfTargetAction(actor, normalizeAssignee(target.getAssignee()));
        mapTarget(target, request, assignee);
        auditSalesTargetMutation(AuditEvent.DATA_UPDATE, target, actor, changeReason);
        return toDto(target);
    }

    public void deleteTarget(Long id, String reason) {
        requireSalesTargetAdminAuthority();
        String actor = currentActorIdentity();
        String changeReason = requireChangeReason(reason, "delete");

        SalesTarget target = requireTarget(id);
        assertNoSelfTargetAction(actor, normalizeAssignee(target.getAssignee()));
        auditSalesTargetMutation(AuditEvent.DATA_DELETE, target, actor, changeReason);
        salesTargetRepository.delete(target);
    }

    private void mapTarget(SalesTarget target, SalesTargetRequest request, String assignee) {
        target.setName(request.name());
        target.setPeriodStart(request.periodStart());
        target.setPeriodEnd(request.periodEnd());
        target.setTargetAmount(request.targetAmount());
        if (request.achievedAmount() != null) {
            target.setAchievedAmount(request.achievedAmount());
        }
        target.setAssignee(assignee);
    }

    private SalesTargetDto toDto(SalesTarget target) {
        return new SalesTargetDto(target.getId(), target.getPublicId(), target.getName(), target.getPeriodStart(),
                target.getPeriodEnd(), target.getTargetAmount(), target.getAchievedAmount(), target.getAssignee());
    }

    private SalesTarget requireTarget(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        return companyEntityLookup.requireSalesTarget(company, id);
    }

    private void requireSalesTargetAdminAuthority() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Sales target operations require authenticated admin authority");
        }
        boolean isAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
        if (!isAdmin) {
            throw new AccessDeniedException("Sales target operations require tenant admin authority");
        }
    }

    private String currentActorIdentity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !StringUtils.hasText(authentication.getName())) {
            return "unknown";
        }
        return authentication.getName().trim();
    }

    private String requireChangeReason(String reason, String operation) {
        if (!StringUtils.hasText(reason)) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Sales target " + operation + " requires changeReason")
                    .withDetail("operation", operation)
                    .withDetail("field", "changeReason");
        }
        return reason.trim();
    }

    private String normalizeAssignee(String assignee) {
        if (!StringUtils.hasText(assignee)) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Sales target assignee is required")
                    .withDetail("field", "assignee");
        }
        return assignee.trim();
    }

    private void assertNoSelfTargetAction(String actor, String assignee) {
        if (!StringUtils.hasText(actor) || !StringUtils.hasText(assignee)) {
            return;
        }
        if (actor.trim().equalsIgnoreCase(assignee.trim())) {
            throw new AccessDeniedException("Self-assignment or self-approval of sales targets is not allowed");
        }
    }

    private void auditSalesTargetMutation(AuditEvent event,
                                          SalesTarget target,
                                          String actor,
                                          String reason) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("actor", actor);
        metadata.put("reason", reason);
        metadata.put("resourceType", "sales_target");
        metadata.put("operation", event.name());
        if (target.getId() != null) {
            metadata.put("targetId", target.getId().toString());
        }
        if (target.getPublicId() != null) {
            metadata.put("targetPublicId", target.getPublicId().toString());
        }
        if (StringUtils.hasText(target.getAssignee())) {
            metadata.put("assignee", target.getAssignee().trim());
        }
        auditService.logSuccess(event, metadata);
    }

    /* Credit Requests */
    public List<CreditRequestDto> listCreditRequests() {
        Company company = companyContextService.requireCurrentCompany();
        return creditRequestRepository.findByCompanyWithDealerOrderByCreatedAtDesc(company).stream().map(this::toDto).toList();
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
        String requestedStatus = normalizeCreditRequestStatus(request.status(), true);
        if (!CREDIT_REQUEST_STATUS_PENDING.equals(requestedStatus)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "New credit requests must start in pending status")
                    .withDetail("requiredStatus", CREDIT_REQUEST_STATUS_PENDING)
                    .withDetail("requestedStatus", requestedStatus);
        }
        creditRequest.setStatus(requestedStatus);
        return toDto(creditRequestRepository.save(creditRequest));
    }

    @Transactional
    public CreditRequestDto updateCreditRequest(Long id, CreditRequestRequest request) {
        CreditRequest creditRequest = requireCreditRequest(id);
        requirePendingCreditRequest(creditRequest, "updated");
        String currentStatus = normalizeCreditRequestStatus(creditRequest.getStatus(), true);
        if (request.dealerId() != null) {
            creditRequest.setDealer(requireDealer(request.dealerId()));
        }
        creditRequest.setAmountRequested(request.amountRequested());
        creditRequest.setReason(request.reason());
        if (request.status() != null) {
            String requestedStatus = normalizeCreditRequestStatus(request.status(), false);
            if (!requestedStatus.equals(currentStatus)) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Credit request status transitions require dedicated approve/reject actions")
                        .withDetail("currentStatus", currentStatus)
                        .withDetail("requestedStatus", requestedStatus);
            }
            creditRequest.setStatus(currentStatus);
        }
        return toDto(creditRequest);
    }

    @Transactional
    public CreditRequestDto approveCreditRequest(Long id, String decisionReason) {
        CreditRequest creditRequest = requireCreditRequest(id);
        requirePendingCreditRequest(creditRequest, "approved");
        String normalizedDecisionReason = requireCreditDecisionReason(decisionReason, "approve");
        creditRequest.setStatus("APPROVED");
        auditCreditRequestDecision(AuditEvent.TRANSACTION_APPROVED, creditRequest, normalizedDecisionReason);
        return toDto(creditRequest);
    }

    @Transactional
    public CreditRequestDto rejectCreditRequest(Long id, String decisionReason) {
        CreditRequest creditRequest = requireCreditRequest(id);
        requirePendingCreditRequest(creditRequest, "rejected");
        String normalizedDecisionReason = requireCreditDecisionReason(decisionReason, "reject");
        creditRequest.setStatus("REJECTED");
        auditCreditRequestDecision(AuditEvent.TRANSACTION_REJECTED, creditRequest, normalizedDecisionReason);
        return toDto(creditRequest);
    }

    private String normalizeCreditRequestStatus(String status, boolean defaultPending) {
        if (!StringUtils.hasText(status)) {
            if (defaultPending) {
                return "PENDING";
            }
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Status is required")
                    .withDetail("entity", "CreditRequest");
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!VALID_CREDIT_REQUEST_STATUSES.contains(normalized)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Unsupported credit request status")
                    .withDetail("status", normalized)
                    .withDetail("allowedStatuses", VALID_CREDIT_REQUEST_STATUSES);
        }
        return normalized;
    }

    private void requirePendingCreditRequest(CreditRequest creditRequest, String action) {
        String currentStatus = normalizeCreditRequestStatus(creditRequest.getStatus(), true);
        if (!CREDIT_REQUEST_STATUS_PENDING.equals(currentStatus)) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Only pending credit requests can be " + action)
                    .withDetail("currentStatus", currentStatus)
                    .withDetail("requiredStatus", CREDIT_REQUEST_STATUS_PENDING);
        }
    }

    private String requireCreditDecisionReason(String reason, String operation) {
        if (!StringUtils.hasText(reason)) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Credit request " + operation + " decision requires reason")
                    .withDetail("field", "reason")
                    .withDetail("operation", operation)
                    .withDetail("resourceType", "credit_request");
        }
        return reason.trim();
    }

    private void auditCreditRequestDecision(AuditEvent event, CreditRequest creditRequest, String decisionReason) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("resourceType", "credit_request");
        metadata.put("decisionStatus", normalizeCreditRequestStatus(creditRequest.getStatus(), false));
        metadata.put("decisionReason", decisionReason);
        metadata.put("reason", decisionReason);
        if (creditRequest.getId() != null) {
            metadata.put("creditRequestId", creditRequest.getId().toString());
        }
        if (creditRequest.getPublicId() != null) {
            metadata.put("creditRequestPublicId", creditRequest.getPublicId().toString());
        }
        if (creditRequest.getDealer() != null && creditRequest.getDealer().getId() != null) {
            metadata.put("dealerId", creditRequest.getDealer().getId().toString());
        }
        if (StringUtils.hasText(creditRequest.getReason())) {
            metadata.put("requestReason", creditRequest.getReason().trim());
        }
        auditService.logSuccess(event, metadata);
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
        Company company = companyContextService.requireCurrentCompany();
        // Ensure company defaults are configured before proceeding
        companyDefaultAccountsService.requireDefaults();
        Long requestedSlipId = request.packingSlipId();
        Long salesOrderId = request.orderId();
        if (requestedSlipId == null && salesOrderId == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "packingSlipId or orderId is required");
        }
        if (requestedSlipId != null) {
            Optional<PackagingSlip> referencedSlipOpt = packagingSlipRepository.findAndLockByIdAndCompany(request.packingSlipId(), company);
            if (referencedSlipOpt == null) {
                referencedSlipOpt = Optional.empty();
            }
            PackagingSlip referencedSlip = referencedSlipOpt
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Packing slip not found"));
            salesOrderId = referencedSlip.getSalesOrder() != null ? referencedSlip.getSalesOrder().getId() : salesOrderId;
        }
        if (salesOrderId == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Sales order is required on packing slip");
        }
        SalesOrder order = requireOrderForUpdate(company, salesOrderId);
        List<PackagingSlip> orderSlips = findOrderSlips(company, salesOrderId, true);
        if (orderSlips.isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Packing slip not found for order " + salesOrderId);
        }
        PackagingSlip slip;
        if (requestedSlipId != null) {
            slip = orderSlips.stream()
                    .filter(candidate -> candidate != null && requestedSlipId.equals(candidate.getId()))
                    .findFirst()
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Packing slip not found"));
        } else {
            long activeSlips = activeSlipCount(orderSlips);
            if (activeSlips > 1) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Multiple packing slips found for order " + salesOrderId + "; provide packingSlipId");
            }
            slip = findSingleActiveSlip(orderSlips);
            if (slip == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "No active packing slip found for order " + salesOrderId);
            }
        }
        Long lockedOrderId = order.getId();
        Long slipOrderId = slip.getSalesOrder() != null ? slip.getSalesOrder().getId() : null;
        if (lockedOrderId != null && slipOrderId != null && !lockedOrderId.equals(slipOrderId)) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Packing slip order linkage changed; retry dispatch")
                    .withDetail("packingSlipId", slip.getId())
                    .withDetail("expectedOrderId", lockedOrderId)
                    .withDetail("actualOrderId", slipOrderId);
        }
        boolean singleActiveSlipForOrder = hasSingleActiveSlip(orderSlips);
        String slipNumber = slip.getSlipNumber();
        String cogsReferenceId = resolveCogsReferenceId(slip, slipNumber);
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
        boolean hasRequestedOverrides = lineOverrides.values().stream().anyMatch(this::isDispatchOverrideApplied);
        boolean hasPriceOverride = lineOverrides.values().stream().anyMatch(line -> line != null && line.priceOverride() != null);
        boolean hasDiscountOverride = lineOverrides.values().stream().anyMatch(line -> line != null && line.discount() != null);
        boolean hasTaxOverride = lineOverrides.values().stream()
                .anyMatch(line -> line != null && (line.taxRate() != null || line.taxInclusive() != null));
        Map<Long, BigDecimal> shipQtyByLineId = new HashMap<>();

        boolean orderTaxInclusive = order.isGstInclusive();
        Map<String, BigDecimal> minPriceBySku = new HashMap<>();
        boolean alreadyDispatched = "DISPATCHED".equalsIgnoreCase(slip.getStatus());
        boolean hasCreditException = Boolean.TRUE.equals(request.adminOverrideCreditLimit()) && !alreadyDispatched;
        boolean hasDispatchException = hasRequestedOverrides || hasCreditException;
        String dispatchReasonCode = resolveDispatchExceptionReasonCode(
                hasCreditException,
                hasPriceOverride,
                hasDiscountOverride,
                hasTaxOverride,
                hasRequestedOverrides);
        if (existingInvoice == null && alreadyDispatched) {
            existingInvoice = resolveExistingInvoiceForSlip(company, order, slip, slipNumber, singleActiveSlipForOrder);
        }
        String overrideReason = null;
        if (alreadyDispatched && hasRequestedOverrides) {
            boolean hasOrderLevelReplayAnchor = order.getSalesJournalEntryId() != null
                    && singleActiveSlipForOrder;
            boolean hasReplayFinancialAnchor = existingInvoice != null
                    || slip.getInvoiceId() != null
                    || slip.getJournalEntryId() != null
                    || hasOrderLevelReplayAnchor;
            if (!hasReplayFinancialAnchor) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Dispatch overrides are not allowed for already dispatched slips without existing financial markers")
                        .withDetail("packingSlipId", slip.getId());
            }
            if (!StringUtils.hasText(request.overrideReason())) {
                throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                        "overrideReason is required when replaying overrides for an already dispatched slip");
            }
            overrideReason = request.overrideReason().trim();
        } else if (hasRequestedOverrides) {
            if (!StringUtils.hasText(request.overrideReason())) {
                throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                        "overrideReason is required when dispatch overrides are applied");
            }
            overrideReason = request.overrideReason().trim();
        } else if (hasCreditException) {
            if (!StringUtils.hasText(request.overrideReason())) {
                throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                        "overrideReason is required when adminOverrideCreditLimit is true")
                        .withDetail("field", "overrideReason");
            }
            overrideReason = request.overrideReason().trim();
        }
        if (alreadyDispatched) {
            Long existingInvoiceId = existingInvoice != null ? existingInvoice.getId() : slip.getInvoiceId();
            Long existingJeId = slip.getJournalEntryId();
            if (existingJeId == null && existingInvoice != null && existingInvoice.getJournalEntry() != null) {
                existingJeId = existingInvoice.getJournalEntry().getId();
            }
            Long existingCogsJournalId = slip.getCogsJournalEntryId();
            if (existingCogsJournalId == null) {
                existingCogsJournalId = findCogsJournalId(company, cogsReferenceId);
                if (existingCogsJournalId == null
                        && StringUtils.hasText(slipNumber)
                        && !slipNumber.trim().equalsIgnoreCase(cogsReferenceId)) {
                    existingCogsJournalId = findCogsJournalId(company, slipNumber.trim());
                }
            }
            boolean hasInvoice = existingInvoiceId != null;
            boolean hasArJournal = existingJeId != null;
            boolean hasCogsJournal = existingCogsJournalId != null
                    || accountingFacade.hasCogsJournalFor(cogsReferenceId)
                    || (StringUtils.hasText(slipNumber)
                    && !slipNumber.trim().equalsIgnoreCase(cogsReferenceId)
                    && accountingFacade.hasCogsJournalFor(slipNumber.trim()));
            if (hasInvoice && hasArJournal && hasCogsJournal) {
                if (existingInvoice != null) {
                    dealerLedgerService.syncInvoiceLedger(existingInvoice, null);
                }
                boolean slipUpdated = false;
                if (slip.getInvoiceId() == null && existingInvoiceId != null) {
                    slip.setInvoiceId(existingInvoiceId);
                    slipUpdated = true;
                }
                if (slip.getJournalEntryId() == null && existingJeId != null) {
                    slip.setJournalEntryId(existingJeId);
                    slipUpdated = true;
                }
                if (slip.getCogsJournalEntryId() == null && existingCogsJournalId != null) {
                    slip.setCogsJournalEntryId(existingCogsJournalId);
                    slipUpdated = true;
                }
                if (slipUpdated) {
                    packagingSlipRepository.save(slip);
                }
                Long cogsMarkerForReconciliation = resolveCogsMarkerForReconciliation(
                        company,
                        order,
                        slip,
                        existingCogsJournalId);
                boolean orderUpdated = reconcileOrderLevelDispatchMarkers(
                        order,
                        existingInvoiceId,
                        existingJeId,
                        cogsMarkerForReconciliation,
                        singleActiveSlipForOrder);
                String nextStatus = resolveOrderStatusAfterDispatch(order, orderSlips);
                if (!nextStatus.equalsIgnoreCase(order.getStatus())) {
                    order.setStatus(nextStatus);
                    orderUpdated = true;
                }
                if (orderUpdated) {
                    salesOrderRepository.save(order);
                }
                if (existingCogsJournalId != null) {
                    finishedGoodsService.linkDispatchMovementsToJournal(slip.getId(), existingCogsJournalId);
                }
                if (hasDispatchException) {
                    requireDispatchExceptionApproval(
                            company,
                            order.getDealer(),
                            slip,
                            order,
                            null,
                            request.overrideRequestId(),
                            dispatchReasonCode
                    );
                }
                logDispatchAudit(slip, order, existingInvoice, existingJeId, existingCogsJournalId,
                        existingInvoice != null ? existingInvoice.getTotalAmount() : null,
                        true,
                        hasDispatchException,
                        overrideReason,
                        dispatchReasonCode,
                        request.overrideRequestId());
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
        Map<Long, BigDecimal> discountByAccount = new HashMap<>();
        Map<Long, BigDecimal> taxByAccount = new HashMap<>();
        List<InvoiceLine> invoiceLines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        Long gstOutputAccountId = null;
        CompanyDefaultAccountsService.DefaultAccounts defaults = companyDefaultAccountsService.getDefaults();

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
            if (discountTotal.compareTo(BigDecimal.ZERO) < 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Dispatch discount cannot be negative")
                        .withDetail("packingSlipLineId", slipLine.getId())
                        .withDetail("discount", discountTotal);
            }
            BigDecimal discountRemaining = discountTotal;
            BigDecimal totalGross = BigDecimal.ZERO;
            if (discountTotal.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal remainingForGross = shipQty;
                SalesOrderItem grossLastItem = null;
                for (OrderItemAllocation allocation : allocations) {
                    if (remainingForGross.compareTo(BigDecimal.ZERO) <= 0) {
                        break;
                    }
                    BigDecimal allocQty = allocation.remaining.min(remainingForGross);
                    if (allocQty.compareTo(BigDecimal.ZERO) <= 0) {
                        continue;
                    }
                    BigDecimal price = override != null && override.priceOverride() != null
                            ? override.priceOverride()
                            : allocation.item.getUnitPrice();
                    totalGross = totalGross.add(price.multiply(allocQty));
                    grossLastItem = allocation.item;
                    remainingForGross = remainingForGross.subtract(allocQty);
                }
                if (remainingForGross.compareTo(BigDecimal.ZERO) > 0 && grossLastItem != null) {
                    BigDecimal price = override != null && override.priceOverride() != null
                            ? override.priceOverride()
                            : grossLastItem.getUnitPrice();
                    totalGross = totalGross.add(price.multiply(remainingForGross));
                }
                if (totalGross.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            "Dispatch discount cannot exceed gross amount")
                            .withDetail("packingSlipLineId", slipLine.getId())
                            .withDetail("discount", discountTotal)
                            .withDetail("grossAmount", totalGross);
                }
                if (discountTotal.compareTo(totalGross) > 0
                        && !MoneyUtils.withinTolerance(discountTotal, totalGross, DISPATCH_TOTAL_TOLERANCE)) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            "Dispatch discount exceeds gross amount")
                            .withDetail("packingSlipLineId", slipLine.getId())
                            .withDetail("discount", discountTotal)
                            .withDetail("grossAmount", totalGross);
                }
            }
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
                if (price.compareTo(BigDecimal.ZERO) < 0) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            "Dispatch price override cannot be negative")
                            .withDetail("productCode", fg.getProductCode())
                            .withDetail("overridePrice", price);
                }
                if (override != null && override.priceOverride() != null) {
                    BigDecimal minAllowed = minPriceBySku.computeIfAbsent(
                            fg.getProductCode(),
                            sku -> resolveMinAllowedPriceForSku(company, sku));
                    if (minAllowed.compareTo(BigDecimal.ZERO) > 0 && price.compareTo(minAllowed) < 0) {
                        throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                                "Dispatch price override is below the minimum allowed")
                                .withDetail("productCode", fg.getProductCode())
                                .withDetail("overridePrice", price)
                                .withDetail("minAllowed", minAllowed);
                    }
                }
                BigDecimal taxRate = override != null && override.taxRate() != null
                        ? normalizePercent(override.taxRate())
                        : (item.getGstRate() == null ? BigDecimal.ZERO : item.getGstRate());
                BigDecimal discount = BigDecimal.ZERO;
                if (discountTotal.compareTo(BigDecimal.ZERO) > 0) {
                    if (lastAllocation) {
                        discount = discountRemaining;
                    } else {
                        BigDecimal allocationBase = totalGross.compareTo(BigDecimal.ZERO) > 0
                                ? totalGross
                                : totalShipQty;
                        BigDecimal weight = totalGross.compareTo(BigDecimal.ZERO) > 0
                                ? price.multiply(allocQty)
                                : allocQty;
                        discount = discountTotal.multiply(weight)
                                .divide(allocationBase, 6, RoundingMode.HALF_UP);
                        discountRemaining = discountRemaining.subtract(discount);
                    }
                }

                BigDecimal lineGross = price.multiply(allocQty);
                if (lineGross.subtract(discount).compareTo(BigDecimal.ZERO) < 0
                        && !MoneyUtils.withinTolerance(lineGross, discount, DISPATCH_TOTAL_TOLERANCE)) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            "Dispatch discount exceeds line gross amount")
                            .withDetail("packingSlipLineId", slipLine.getId())
                            .withDetail("lineGross", lineGross)
                            .withDetail("lineDiscount", discount);
                }
                boolean taxInclusive = override != null && override.taxInclusive() != null
                        ? override.taxInclusive()
                        : orderTaxInclusive;
                LineAmounts amounts = computeDispatchLineAmounts(
                        lineGross,
                        discount,
                        taxRate,
                        taxInclusive);
                BigDecimal lineNet = amounts.net();
                BigDecimal lineTax = amounts.tax();
                BigDecimal lineTotal = amounts.total();
                LineAmounts grossAmounts = computeDispatchLineAmounts(lineGross, BigDecimal.ZERO, taxRate, taxInclusive);
                BigDecimal grossNet = grossAmounts.net();
                BigDecimal discountNet = currency(grossNet.subtract(lineNet));
                if (discountNet.compareTo(BigDecimal.ZERO) < 0
                        && MoneyUtils.withinTolerance(grossNet, lineNet, DISPATCH_TOTAL_TOLERANCE)) {
                    discountNet = BigDecimal.ZERO;
                }

                subtotal = subtotal.add(lineNet);
                taxTotal = taxTotal.add(lineTax);

                if (fg.getRevenueAccountId() != null) {
                    revenueByAccount.merge(fg.getRevenueAccountId(), grossNet, BigDecimal::add);
                }
                if (discountNet.compareTo(BigDecimal.ZERO) > 0) {
                    Long discountAccountId = resolveDiscountAccountId(fg, defaults);
                    if (discountAccountId == null) {
                        throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                                "Discount account is required when a dispatch discount is applied")
                                .withDetail("productCode", fg.getProductCode())
                                .withDetail("packingSlipLineId", slipLine.getId())
                                .withDetail("discountAmount", discountNet);
                    }
                    discountByAccount.merge(discountAccountId, discountNet, BigDecimal::add);
                }
                if (lineTax.compareTo(BigDecimal.ZERO) > 0) {
                    gstOutputAccountId = resolveGstOutputAccountForTaxableDispatchLine(
                            fg,
                            slipLine.getId(),
                            lineTax,
                            gstOutputAccountId);
                    taxByAccount.merge(gstOutputAccountId, lineTax, BigDecimal::add);
                }

                InvoiceLine invLine = new InvoiceLine();
                invLine.setProductCode(fg.getProductCode());
                invLine.setDescription(item.getDescription());
                invLine.setQuantity(allocQty);
                invLine.setUnitPrice(price);
                invLine.setTaxRate(taxRate);
                invLine.setDiscountAmount(discount);
                invLine.setTaxableAmount(lineNet);
                invLine.setTaxAmount(lineTax);
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
                if (price.compareTo(BigDecimal.ZERO) < 0) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            "Dispatch price override cannot be negative")
                            .withDetail("productCode", fg.getProductCode())
                            .withDetail("overridePrice", price);
                }
                if (override != null && override.priceOverride() != null) {
                    BigDecimal minAllowed = minPriceBySku.computeIfAbsent(
                            fg.getProductCode(),
                            sku -> resolveMinAllowedPriceForSku(company, sku));
                    if (minAllowed.compareTo(BigDecimal.ZERO) > 0 && price.compareTo(minAllowed) < 0) {
                        throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                                "Dispatch price override is below the minimum allowed")
                                .withDetail("productCode", fg.getProductCode())
                                .withDetail("overridePrice", price)
                                .withDetail("minAllowed", minAllowed);
                    }
                }
                BigDecimal taxRate = override != null && override.taxRate() != null
                        ? normalizePercent(override.taxRate())
                        : (lastItem.getGstRate() == null ? BigDecimal.ZERO : lastItem.getGstRate());
                BigDecimal discount = discountRemaining;
                BigDecimal lineGross = price.multiply(remainingToAllocate);
                if (lineGross.subtract(discount).compareTo(BigDecimal.ZERO) < 0
                        && !MoneyUtils.withinTolerance(lineGross, discount, DISPATCH_TOTAL_TOLERANCE)) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            "Dispatch discount exceeds line gross amount")
                            .withDetail("packingSlipLineId", slipLine.getId())
                            .withDetail("lineGross", lineGross)
                            .withDetail("lineDiscount", discount);
                }
                boolean taxInclusive = override != null && override.taxInclusive() != null
                        ? override.taxInclusive()
                        : orderTaxInclusive;
                LineAmounts amounts = computeDispatchLineAmounts(
                        lineGross,
                        discount,
                        taxRate,
                        taxInclusive);
                BigDecimal lineNet = amounts.net();
                BigDecimal lineTax = amounts.tax();
                BigDecimal lineTotal = amounts.total();
                LineAmounts grossAmounts = computeDispatchLineAmounts(lineGross, BigDecimal.ZERO, taxRate, taxInclusive);
                BigDecimal grossNet = grossAmounts.net();
                BigDecimal discountNet = currency(grossNet.subtract(lineNet));
                if (discountNet.compareTo(BigDecimal.ZERO) < 0
                        && MoneyUtils.withinTolerance(grossNet, lineNet, DISPATCH_TOTAL_TOLERANCE)) {
                    discountNet = BigDecimal.ZERO;
                }

                subtotal = subtotal.add(lineNet);
                taxTotal = taxTotal.add(lineTax);
                if (fg.getRevenueAccountId() != null) {
                    revenueByAccount.merge(fg.getRevenueAccountId(), grossNet, BigDecimal::add);
                }
                if (discountNet.compareTo(BigDecimal.ZERO) > 0) {
                    Long discountAccountId = resolveDiscountAccountId(fg, defaults);
                    if (discountAccountId == null) {
                        throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                                "Discount account is required when a dispatch discount is applied")
                                .withDetail("productCode", fg.getProductCode())
                                .withDetail("packingSlipLineId", slipLine.getId())
                                .withDetail("discountAmount", discountNet);
                    }
                    discountByAccount.merge(discountAccountId, discountNet, BigDecimal::add);
                }
                if (lineTax.compareTo(BigDecimal.ZERO) > 0) {
                    gstOutputAccountId = resolveGstOutputAccountForTaxableDispatchLine(
                            fg,
                            slipLine.getId(),
                            lineTax,
                            gstOutputAccountId);
                    taxByAccount.merge(gstOutputAccountId, lineTax, BigDecimal::add);
                }

                InvoiceLine invLine = new InvoiceLine();
                invLine.setProductCode(fg.getProductCode());
                invLine.setDescription(lastItem.getDescription());
                invLine.setQuantity(remainingToAllocate);
                invLine.setUnitPrice(price);
                invLine.setTaxRate(taxRate);
                invLine.setDiscountAmount(discount);
                invLine.setTaxableAmount(lineNet);
                invLine.setTaxAmount(lineTax);
                invLine.setLineTotal(lineTotal);
                invoiceLines.add(invLine);
            }
        }

        BigDecimal totalAmount = subtotal.add(taxTotal);
        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "No shippable quantity available for dispatch");
        }

        if (hasDispatchException) {
            requireDispatchExceptionApproval(
                    company,
                    dealer,
                    slip,
                    order,
                    totalAmount,
                    request.overrideRequestId(),
                    dispatchReasonCode
            );
        }

        if (!hasCreditException) {
            enforceDispatchCreditLimit(company, dealer, slip, order, totalAmount, request.overrideRequestId());
        }

        if (existingInvoice != null && existingInvoice.getTotalAmount() != null
                && !MoneyUtils.withinTolerance(existingInvoice.getTotalAmount(), totalAmount, DISPATCH_TOTAL_TOLERANCE)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Existing invoice total does not match dispatch total; void invoice or reissue via dispatch")
                    .withDetail("invoiceTotal", existingInvoice.getTotalAmount())
                    .withDetail("dispatchTotal", totalAmount);
        }
        Long preexistingJournalId = null;
        if (existingInvoice != null && existingInvoice.getJournalEntry() != null) {
            preexistingJournalId = existingInvoice.getJournalEntry().getId();
        } else if (slip.getJournalEntryId() != null) {
            preexistingJournalId = slip.getJournalEntryId();
        } else if (alreadyDispatched && order.getSalesJournalEntryId() != null && singleActiveSlipForOrder) {
            preexistingJournalId = order.getSalesJournalEntryId();
        }
        if (preexistingJournalId != null) {
            validateExistingReceivableJournal(company, preexistingJournalId, dealer, totalAmount);
        }

        if (!alreadyDispatched) {
            String dispatchNotes = request.dispatchNotes();
            if (overrideReason != null) {
                dispatchNotes = formatDispatchNotesWithOverrideReason(dispatchNotes, overrideReason);
            }
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
                            dispatchNotes,
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
                        cogsReferenceId,
                        dealer.getId(),
                        dispatchedDate,
                        "COGS for dispatch " + slipNumber,
                        cogsLines
                );
                cogsJournalId = cogsEntry != null ? cogsEntry.id() : null;
            }
        }
        if (cogsJournalId != null) {
            finishedGoodsService.linkDispatchMovementsToJournal(slip.getId(), cogsJournalId);
        }

        String invoiceNumber = existingInvoice != null
                ? existingInvoice.getInvoiceNumber()
                : invoiceNumberService.nextInvoiceNumber(company);

        Long arJournalEntryId = existingInvoice != null && existingInvoice.getJournalEntry() != null
                ? existingInvoice.getJournalEntry().getId()
                : null;
        if (arJournalEntryId == null && preexistingJournalId != null) {
            arJournalEntryId = preexistingJournalId;
        }
        boolean singleSlipForOrder = singleActiveSlipForOrder;
        List<DispatchConfirmResponse.AccountPostingDto> arPostings = new ArrayList<>();
        if (arJournalEntryId == null && totalAmount.compareTo(BigDecimal.ZERO) > 0) {
            if (revenueByAccount.isEmpty()) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "No revenue or tax account configured for dispatched items");
            }
            LocalDate dispatchDate = dispatchedDate;
            arPostings.add(toPosting(company, dealer.getReceivableAccount().getId(), "AR for dispatch " + slipNumber, totalAmount, BigDecimal.ZERO));
            for (var entry : revenueByAccount.entrySet()) {
                arPostings.add(toPosting(company, entry.getKey(), "Revenue for dispatch " + slipNumber, BigDecimal.ZERO, entry.getValue()));
            }
            for (var entry : discountByAccount.entrySet()) {
                arPostings.add(toPosting(company, entry.getKey(), "Discount for dispatch " + slipNumber, entry.getValue(), BigDecimal.ZERO));
            }
            for (var entry : taxByAccount.entrySet()) {
                arPostings.add(toPosting(company, entry.getKey(), "Tax for dispatch " + slipNumber, BigDecimal.ZERO, entry.getValue()));
            }
            String salesJournalOrderKey = resolveSalesJournalOrderKey(order, slip.getId(), slipNumber, singleSlipForOrder);
            var arEntry = accountingFacade.postSalesJournal(
                    dealer.getId(),
                    salesJournalOrderKey,
                    dispatchDate,
                    "Dispatch " + slipNumber,
                    revenueByAccount,
                    taxByAccount,
                    discountByAccount.isEmpty() ? null : discountByAccount,
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

        List<PackagingSlip> latestOrderSlips = findOrderSlips(company, order.getId(), false);
        if (latestOrderSlips.isEmpty() && slip != null) {
            latestOrderSlips = List.of(slip);
        }
        boolean singleActiveSlipAfterDispatch = hasSingleActiveSlip(latestOrderSlips);
        if (singleActiveSlipAfterDispatch) {
            order.setSalesJournalEntryId(arJournalEntryId);
            order.setCogsJournalEntryId(cogsJournalId);
            order.setFulfillmentInvoiceId(invoice.getId());
        }
        boolean orderUpdated = reconcileOrderLevelDispatchMarkers(
                order,
                invoice.getId(),
                arJournalEntryId,
                cogsJournalId,
                singleActiveSlipAfterDispatch);
        String nextStatus = resolveOrderStatusAfterDispatch(order, latestOrderSlips);
        if (!nextStatus.equalsIgnoreCase(order.getStatus())) {
            order.setStatus(nextStatus);
            orderUpdated = true;
        }
        if (orderUpdated) {
            salesOrderRepository.save(order);
        }

        logDispatchAudit(
                slip,
                order,
                invoice,
                arJournalEntryId,
                cogsJournalId,
                totalAmount,
                alreadyDispatched,
                hasDispatchException,
                overrideReason,
                dispatchReasonCode,
                request.overrideRequestId()
        );
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

    private void validateExistingReceivableJournal(Company company,
                                                   Long journalEntryId,
                                                   Dealer dealer,
                                                   BigDecimal expectedTotal) {
        if (journalEntryId == null || dealer == null || dealer.getReceivableAccount() == null) {
            return;
        }
        JournalEntry entry = companyEntityLookup.requireJournalEntry(company, journalEntryId);
        Long receivableAccountId = dealer.getReceivableAccount().getId();
        if (receivableAccountId == null || entry.getLines() == null || entry.getLines().isEmpty()) {
            return;
        }
        BigDecimal receivableTotal = BigDecimal.ZERO;
        for (JournalLine line : entry.getLines()) {
            if (line.getAccount() != null && receivableAccountId.equals(line.getAccount().getId())) {
                receivableTotal = receivableTotal.add(line.getDebit().subtract(line.getCredit()));
            }
        }
        if (!MoneyUtils.withinTolerance(receivableTotal.abs(), expectedTotal.abs(), DISPATCH_TOTAL_TOLERANCE)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Existing AR journal total does not match dispatch total; void entry or reissue via dispatch")
                    .withDetail("journalEntryId", journalEntryId)
                    .withDetail("journalTotal", receivableTotal)
                    .withDetail("dispatchTotal", expectedTotal);
        }
    }

    private void logDispatchAudit(PackagingSlip slip,
                                  SalesOrder order,
                                  Invoice invoice,
                                  Long arJournalEntryId,
                                  Long cogsJournalEntryId,
                                  BigDecimal totalAmount,
                                  boolean alreadyDispatched,
                                  boolean hasOverrides,
                                  String overrideReason,
                                  String overrideReasonCode,
                                  Long overrideRequestId) {
        Map<String, String> metadata = new HashMap<>();
        if (slip != null && slip.getId() != null) {
            metadata.put("packingSlipId", slip.getId().toString());
        }
        if (slip != null && StringUtils.hasText(slip.getSlipNumber())) {
            metadata.put("packingSlipNumber", slip.getSlipNumber());
        }
        if (order != null && order.getId() != null) {
            metadata.put("salesOrderId", order.getId().toString());
        }
        if (order != null && StringUtils.hasText(order.getOrderNumber())) {
            metadata.put("salesOrderNumber", order.getOrderNumber());
        }
        String traceId = order != null ? normalizeTraceId(order.getTraceId()) : null;
        if (StringUtils.hasText(traceId)) {
            metadata.put("traceId", traceId);
        }
        if (invoice != null && invoice.getId() != null) {
            metadata.put("invoiceId", invoice.getId().toString());
        }
        if (arJournalEntryId != null) {
            metadata.put("arJournalEntryId", arJournalEntryId.toString());
        }
        if (cogsJournalEntryId != null) {
            metadata.put("cogsJournalEntryId", cogsJournalEntryId.toString());
        }
        if (totalAmount != null) {
            metadata.put("dispatchTotal", totalAmount.toPlainString());
        }
        metadata.put("alreadyDispatched", Boolean.toString(alreadyDispatched));
        metadata.put("dispatchOverridesApplied", Boolean.toString(hasOverrides));
        if (StringUtils.hasText(overrideReason)) {
            metadata.put("dispatchOverrideReason", overrideReason.trim());
        }
        if (StringUtils.hasText(overrideReasonCode)) {
            metadata.put("dispatchOverrideReasonCode", overrideReasonCode);
        }
        if (overrideRequestId != null) {
            metadata.put("overrideRequestId", overrideRequestId.toString());
        }
        auditService.logSuccess(AuditEvent.DISPATCH_CONFIRMED, metadata);
    }

    private void requireDispatchExceptionApproval(Company company,
                                                  Dealer dealer,
                                                  PackagingSlip slip,
                                                  SalesOrder order,
                                                  BigDecimal dispatchAmount,
                                                  Long overrideRequestId,
                                                  String reasonCode) {
        if (overrideRequestId == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "overrideRequestId is required when dispatch exceptions are applied")
                    .withDetail("field", "overrideRequestId")
                    .withDetail("reasonCode", reasonCode);
        }
        boolean approved = creditLimitOverrideService.isOverrideApproved(
                overrideRequestId,
                company,
                dealer,
                slip,
                order,
                dispatchAmount
        );
        if (approved) {
            return;
        }
        throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                "Dispatch exceptions require an approved maker-checker override request")
                .withDetail("overrideRequestId", overrideRequestId)
                .withDetail("reasonCode", reasonCode);
    }

    private String resolveDispatchExceptionReasonCode(boolean hasCreditException,
                                                      boolean hasPriceOverride,
                                                      boolean hasDiscountOverride,
                                                      boolean hasTaxOverride,
                                                      boolean hasAnyLineOverride) {
        int lineOverrideKinds = 0;
        if (hasPriceOverride) {
            lineOverrideKinds++;
        }
        if (hasDiscountOverride) {
            lineOverrideKinds++;
        }
        if (hasTaxOverride) {
            lineOverrideKinds++;
        }
        if (!hasCreditException && lineOverrideKinds == 0) {
            return null;
        }
        if (hasCreditException && lineOverrideKinds == 0) {
            return DISPATCH_REASON_CODE_CREDIT_LIMIT;
        }
        if (!hasCreditException && lineOverrideKinds == 1) {
            if (hasPriceOverride) {
                return DISPATCH_REASON_CODE_PRICE_OVERRIDE;
            }
            if (hasDiscountOverride) {
                return DISPATCH_REASON_CODE_DISCOUNT_OVERRIDE;
            }
            return DISPATCH_REASON_CODE_TAX_OVERRIDE;
        }
        if (!hasCreditException && hasAnyLineOverride && lineOverrideKinds == 0) {
            return DISPATCH_REASON_CODE_LINE_OVERRIDE;
        }
        return DISPATCH_REASON_CODE_COMPOSITE_OVERRIDE;
    }

    private boolean isDispatchOverrideApplied(DispatchConfirmRequest.DispatchLine line) {
        if (line == null) {
            return false;
        }
        return line.priceOverride() != null
                || line.discount() != null
                || line.taxRate() != null
                || line.taxInclusive() != null;
    }

    private String formatDispatchNotesWithOverrideReason(String dispatchNotes, String overrideReason) {
        String base = StringUtils.hasText(dispatchNotes) ? dispatchNotes.trim() : "";
        String reason = StringUtils.hasText(overrideReason) ? overrideReason.trim() : "";
        if (!StringUtils.hasText(reason)) {
            return base;
        }
        String combined = base.isEmpty()
                ? "Override reason: " + reason
                : base + " | Override reason: " + reason;
        if (combined.length() > 1000) {
            combined = combined.substring(0, 1000);
        }
        return combined;
    }

    private String normalizeTraceId(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return null;
        }
        return traceId.trim();
    }

    private String normalizeStatusToken(String status) {
        if (!StringUtils.hasText(status)) {
            return "";
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private String buildCogsReference(String referenceId) {
        return SalesOrderReference.cogsReference(referenceId);
    }

    private Long findCogsJournalId(Company company, String referenceId) {
        if (!StringUtils.hasText(referenceId)) {
            return null;
        }
        String cogsReference = buildCogsReference(referenceId.trim());
        return journalEntryRepository.findByCompanyAndReferenceNumber(company, cogsReference)
                .map(JournalEntry::getId)
                .orElse(null);
    }

    private String resolveCogsReferenceId(PackagingSlip slip, String slipNumber) {
        if (slip != null && slip.getId() != null) {
            return "PS-" + slip.getId();
        }
        if (StringUtils.hasText(slipNumber)) {
            return slipNumber.trim();
        }
        return "PS-GEN";
    }

    private String resolveSalesJournalOrderKey(SalesOrder order, Long slipId, String slipNumber, boolean singleSlip) {
        String base = order != null ? order.getOrderNumber() : null;
        if (!StringUtils.hasText(base) && order != null && order.getId() != null) {
            base = order.getId().toString();
        }
        if (!StringUtils.hasText(base)) {
            base = "UNKNOWN";
        }
        if (!singleSlip) {
            if (slipId != null) {
                return base + "-PS-" + slipId;
            }
            if (StringUtils.hasText(slipNumber)) {
                return base + "-PS-" + normalizeReferenceToken(slipNumber);
            }
        }
        return base;
    }

    private String normalizeReferenceToken(String value) {
        if (!StringUtils.hasText(value)) {
            return "GEN";
        }
        return value.replaceAll("[^A-Za-z0-9-]", "").toUpperCase();
    }

    private DispatchConfirmResponse.AccountPostingDto toPosting(Company company, Long accountId, String label, BigDecimal debit, BigDecimal credit) {
        if (accountId == null) {
            return new DispatchConfirmResponse.AccountPostingDto(null, label, debit, credit);
        }
        String name = accountRepository.findByCompanyAndId(company, accountId)
                .map(Account::getName)
                .orElse("Account " + accountId);
        return new DispatchConfirmResponse.AccountPostingDto(accountId, name, debit, credit);
    }

    private Long resolveDiscountAccountId(FinishedGood finishedGood, CompanyDefaultAccountsService.DefaultAccounts defaults) {
        if (finishedGood != null && finishedGood.getDiscountAccountId() != null) {
            return finishedGood.getDiscountAccountId();
        }
        return defaults != null ? defaults.discountAccountId() : null;
    }

    private Long resolveGstOutputAccountForTaxableDispatchLine(FinishedGood finishedGood,
                                                               Long packingSlipLineId,
                                                               BigDecimal lineTax,
                                                               Long currentOutputAccountId) {
        if (lineTax == null || lineTax.compareTo(BigDecimal.ZERO) <= 0) {
            return currentOutputAccountId;
        }
        if (finishedGood == null || finishedGood.getTaxAccountId() == null) {
            String productCode = finishedGood != null ? finishedGood.getProductCode() : "UNKNOWN";
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Finished good " + productCode + " is missing GST liability account for taxable dispatch")
                    .withDetail("packingSlipLineId", packingSlipLineId)
                    .withDetail("lineTax", lineTax);
        }
        Long outputAccountId = currentOutputAccountId;
        if (outputAccountId == null) {
            outputAccountId = companyAccountingSettingsService.requireTaxAccounts().outputTaxAccountId();
        }
        if (!finishedGood.getTaxAccountId().equals(outputAccountId)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Finished good " + finishedGood.getProductCode() + " tax account must match GST output account")
                    .withDetail("packingSlipLineId", packingSlipLineId)
                    .withDetail("finishedGoodTaxAccountId", finishedGood.getTaxAccountId())
                    .withDetail("gstOutputAccountId", outputAccountId);
        }
        return outputAccountId;
    }

    private LineAmounts computeDispatchLineAmounts(BigDecimal gross,
                                                   BigDecimal discount,
                                                   BigDecimal taxRate,
                                                   boolean taxInclusive) {
        BigDecimal lineGross = gross != null ? gross : BigDecimal.ZERO;
        BigDecimal lineDiscount = discount != null ? discount : BigDecimal.ZERO;
        BigDecimal rate = taxRate != null ? taxRate : BigDecimal.ZERO;

        BigDecimal netRaw = lineGross.subtract(lineDiscount);
        BigDecimal taxRaw = BigDecimal.ZERO;
        if (taxInclusive && rate.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal divisor = BigDecimal.ONE.add(rate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
            if (divisor.signum() > 0) {
                BigDecimal preTax = netRaw.divide(divisor, 6, RoundingMode.HALF_UP);
                taxRaw = netRaw.subtract(preTax);
                netRaw = preTax;
            }
        } else if (rate.compareTo(BigDecimal.ZERO) > 0) {
            taxRaw = netRaw.multiply(rate).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        }

        BigDecimal net = currency(netRaw);
        BigDecimal tax = currency(taxRaw);
        return new LineAmounts(net, tax, net.add(tax));
    }

    private static final class OrderItemAllocation {
        private final SalesOrderItem item;
        private BigDecimal remaining;

        private OrderItemAllocation(SalesOrderItem item, BigDecimal remaining) {
            this.item = item;
            this.remaining = remaining != null ? remaining : BigDecimal.ZERO;
        }
    }

    private record LineAmounts(BigDecimal net, BigDecimal tax, BigDecimal total) {}

    private Invoice resolveExistingInvoiceForSlip(Company company,
                                                  SalesOrder order,
                                                  PackagingSlip slip,
                                                  String slipNumber,
                                                  boolean singleActiveSlipForOrder) {
        if (company == null || order == null || order.getId() == null || slip == null) {
            return null;
        }
        List<Invoice> orderInvoices = invoiceRepository.findAllByCompanyAndSalesOrderId(company, order.getId());
        if (orderInvoices.isEmpty()) {
            return null;
        }
        if (StringUtils.hasText(slipNumber)) {
            String dispatchNote = "Dispatch " + slipNumber.trim();
            List<Invoice> noteMatches = orderInvoices.stream()
                    .filter(inv -> StringUtils.hasText(inv.getNotes())
                            && dispatchNote.equalsIgnoreCase(inv.getNotes().trim()))
                    .toList();
            if (noteMatches.size() == 1) {
                return noteMatches.getFirst();
            }
            if (noteMatches.size() > 1) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Multiple invoices match dispatch slip; manual reconciliation required")
                        .withDetail("packingSlipId", slip.getId())
                        .withDetail("slipNumber", slipNumber);
            }
        }
        if (singleActiveSlipForOrder) {
            if (order.getFulfillmentInvoiceId() != null) {
                return invoiceRepository.findByCompanyAndId(company, order.getFulfillmentInvoiceId()).orElse(null);
            }
            if (orderInvoices.size() == 1) {
                return orderInvoices.getFirst();
            }
        }
        return null;
    }

    private boolean reconcileOrderLevelDispatchMarkers(SalesOrder order,
                                                       Long invoiceId,
                                                       Long salesJournalEntryId,
                                                       Long cogsJournalEntryId,
                                                       boolean singleSlipForOrder) {
        if (order == null) {
            return false;
        }
        boolean orderUpdated = false;
        if (!singleSlipForOrder) {
            if (order.getSalesJournalEntryId() != null) {
                order.setSalesJournalEntryId(null);
                orderUpdated = true;
            }
            if (order.getCogsJournalEntryId() != null) {
                order.setCogsJournalEntryId(null);
                orderUpdated = true;
            }
            if (order.getFulfillmentInvoiceId() != null) {
                order.setFulfillmentInvoiceId(null);
                orderUpdated = true;
            }
            return orderUpdated;
        }
        if (!java.util.Objects.equals(order.getSalesJournalEntryId(), salesJournalEntryId)) {
            order.setSalesJournalEntryId(salesJournalEntryId);
            orderUpdated = true;
        }
        if (!java.util.Objects.equals(order.getCogsJournalEntryId(), cogsJournalEntryId)) {
            order.setCogsJournalEntryId(cogsJournalEntryId);
            orderUpdated = true;
        }
        if (!java.util.Objects.equals(order.getFulfillmentInvoiceId(), invoiceId)) {
            order.setFulfillmentInvoiceId(invoiceId);
            orderUpdated = true;
        }
        return orderUpdated;
    }

    private String resolveOrderStatusAfterDispatch(SalesOrder order, List<PackagingSlip> slips) {
        if (order == null) {
            return null;
        }
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
        return companyClock.today(company);
    }

}
