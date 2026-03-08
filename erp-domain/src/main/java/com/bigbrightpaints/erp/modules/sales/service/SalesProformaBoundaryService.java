package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.CreditLimitExceededException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

final class SalesProformaBoundaryService {

    static final String DEFAULT_PAYMENT_MODE = "CREDIT";
    private static final String CASH_PAYMENT_MODE = "CASH";
    private static final String HYBRID_PAYMENT_MODE = "HYBRID";
    private static final String LEGACY_HYBRID_PAYMENT_MODE = "SPLIT";
    private static final Set<String> VALID_PAYMENT_MODES = Set.of(
            CASH_PAYMENT_MODE,
            DEFAULT_PAYMENT_MODE,
            HYBRID_PAYMENT_MODE
    );
    private static final String REQUIREMENT_TITLE_PREFIX = "Production requirement: ";
    private static final Set<String> CLOSED_REQUIREMENT_STATUSES = Set.of("COMPLETED", "CANCELLED");

    private final DealerRepository dealerRepository;
    private final DealerLedgerService dealerLedgerService;
    private final SalesOrderRepository salesOrderRepository;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final FactoryTaskRepository factoryTaskRepository;
    private final CompanyClock companyClock;

    SalesProformaBoundaryService(DealerRepository dealerRepository,
                                 DealerLedgerService dealerLedgerService,
                                 SalesOrderRepository salesOrderRepository,
                                 FinishedGoodRepository finishedGoodRepository,
                                 FinishedGoodBatchRepository finishedGoodBatchRepository,
                                 FactoryTaskRepository factoryTaskRepository,
                                 CompanyClock companyClock) {
        this.dealerRepository = dealerRepository;
        this.dealerLedgerService = dealerLedgerService;
        this.salesOrderRepository = salesOrderRepository;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.factoryTaskRepository = factoryTaskRepository;
        this.companyClock = companyClock;
    }

    String normalizePaymentMode(String rawMode) {
        String normalized = StringUtils.hasText(rawMode)
                ? rawMode.trim().toUpperCase(Locale.ROOT)
                : DEFAULT_PAYMENT_MODE;
        if (LEGACY_HYBRID_PAYMENT_MODE.equals(normalized)) {
            return HYBRID_PAYMENT_MODE;
        }
        if (!VALID_PAYMENT_MODES.contains(normalized)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Unsupported sales order payment mode: " + normalized)
                    .withDetail("paymentMode", normalized)
                    .withDetail("allowedPaymentModes", VALID_PAYMENT_MODES)
                    .withDetail("legacyAliases", Map.of(LEGACY_HYBRID_PAYMENT_MODE, HYBRID_PAYMENT_MODE));
        }
        return normalized;
    }

    boolean requiresCreditCheck(String paymentMode) {
        return !CASH_PAYMENT_MODE.equals(normalizePaymentMode(paymentMode));
    }

    Dealer resolveDealerForProforma(Company company, Long dealerId, String paymentMode) {
        if (dealerId == null) {
            return null;
        }
        Dealer dealer = dealerRepository.lockByCompanyAndId(company, dealerId)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Dealer not found"));
        if ("ON_HOLD".equalsIgnoreCase(dealer.getStatus())) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Dealer " + dealer.getName() + " is on hold");
        }
        return dealer;
    }

    void enforceCreditPosture(Company company,
                              Dealer dealer,
                              BigDecimal proformaAmount,
                              String paymentMode,
                              Long excludeOrderId) {
        String normalizedPaymentMode = normalizePaymentMode(paymentMode);
        if (!requiresCreditCheck(normalizedPaymentMode)) {
            return;
        }
        if (dealer == null || dealer.getId() == null) {
            return;
        }
        Dealer lockedDealer = dealerRepository.lockByCompanyAndId(company, dealer.getId())
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Dealer not found"));
        if (lockedDealer.getReceivableAccount() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_STATE,
                    "Dealer onboarding is incomplete; receivable account is required before credit-backed proformas")
                    .withDetail("dealerId", lockedDealer.getId())
                    .withDetail("paymentMode", normalizedPaymentMode);
        }
        BigDecimal creditLimit = lockedDealer.getCreditLimit();
        if (creditLimit == null || creditLimit.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal outstandingBalance = safe(dealerLedgerService.currentBalance(lockedDealer.getId()));
        BigDecimal pendingOrderExposure = safe(salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(
                company,
                lockedDealer,
                SalesOrderCreditExposurePolicy.pendingCreditExposureStatuses(),
                excludeOrderId
        ));
        BigDecimal currentExposure = outstandingBalance.add(pendingOrderExposure);
        BigDecimal orderAmount = safe(proformaAmount);
        BigDecimal projectedExposure = currentExposure.add(orderAmount);
        if (projectedExposure.compareTo(creditLimit) <= 0) {
            return;
        }
        BigDecimal requiredHeadroom = projectedExposure.subtract(creditLimit).max(BigDecimal.ZERO);
        CreditLimitExceededException ex = new CreditLimitExceededException(String.format(
                "%s payment mode would exceed dealer %s credit posture. Limit %.2f, outstanding %.2f, pending %.2f, attempted proforma %.2f",
                normalizedPaymentMode,
                lockedDealer.getName(),
                creditLimit,
                outstandingBalance,
                pendingOrderExposure,
                orderAmount));
        ex.withDetail("dealerId", lockedDealer.getId())
                .withDetail("companyId", company.getId())
                .withDetail("paymentMode", normalizedPaymentMode)
                .withDetail("outstandingBalance", outstandingBalance)
                .withDetail("pendingOrderExposure", pendingOrderExposure)
                .withDetail("currentExposure", currentExposure)
                .withDetail("creditLimit", creditLimit)
                .withDetail("orderAmount", orderAmount)
                .withDetail("projectedExposure", projectedExposure)
                .withDetail("requiredHeadroom", requiredHeadroom)
                .withDetail("approvalRequired", true);
        throw ex;
    }

    CommercialAssessment assessCommercialAvailability(Company company, SalesOrder order) {
        Map<String, BigDecimal> requestedQuantityBySku = new LinkedHashMap<>();
        Map<String, String> productNamesBySku = new LinkedHashMap<>();
        for (SalesOrderItem item : order.getItems()) {
            if (!StringUtils.hasText(item.getProductCode())) {
                continue;
            }
            String sku = item.getProductCode().trim().toUpperCase(Locale.ROOT);
            requestedQuantityBySku.merge(sku, safe(item.getQuantity()), BigDecimal::add);
            productNamesBySku.putIfAbsent(sku,
                    StringUtils.hasText(item.getDescription()) ? item.getDescription().trim() : sku);
        }

        List<FinishedGoodsService.InventoryShortage> shortages = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : requestedQuantityBySku.entrySet()) {
            if (entry.getValue().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String sku = entry.getKey();
            FinishedGood finishedGood = finishedGoodRepository.findByCompanyAndProductCode(company, sku).orElse(null);
            if (finishedGood == null) {
                shortages.add(new FinishedGoodsService.InventoryShortage(
                        sku,
                        entry.getValue(),
                        productNamesBySku.getOrDefault(sku, sku)
                ));
                continue;
            }
            BigDecimal availableQuantity = finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(finishedGood)
                    .stream()
                    .map(FinishedGoodBatch::getQuantityAvailable)
                    .map(this::safe)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal shortageQuantity = entry.getValue().subtract(availableQuantity);
            if (shortageQuantity.compareTo(BigDecimal.ZERO) > 0) {
                shortages.add(new FinishedGoodsService.InventoryShortage(
                        sku,
                        shortageQuantity,
                        productNamesBySku.getOrDefault(sku, sku)
                ));
            }
        }

        syncProductionRequirements(company, order, shortages);
        return new CommercialAssessment(shortages.isEmpty() ? "RESERVED" : "PENDING_PRODUCTION", List.copyOf(shortages));
    }

    List<String> openProductionRequirementSkus(Company company, Long salesOrderId) {
        if (salesOrderId == null) {
            return List.of();
        }
        List<String> skus = new ArrayList<>();
        for (FactoryTask task : factoryTaskRepository.findByCompanyAndSalesOrderId(company, salesOrderId)) {
            if (!isManagedRequirement(task) || isClosedRequirement(task)) {
                continue;
            }
            skus.add(extractSku(task.getTitle()));
        }
        return skus;
    }

    private void syncProductionRequirements(Company company,
                                            SalesOrder order,
                                            List<FinishedGoodsService.InventoryShortage> shortages) {
        if (order.getId() == null) {
            return;
        }
        Map<String, FactoryTask> existingRequirementsBySku = new LinkedHashMap<>();
        for (FactoryTask task : factoryTaskRepository.findByCompanyAndSalesOrderId(company, order.getId())) {
            if (isManagedRequirement(task)) {
                existingRequirementsBySku.put(extractSku(task.getTitle()), task);
            }
        }

        LocalDate today = companyClock.today(company);
        List<FactoryTask> dirtyTasks = new ArrayList<>();
        Set<String> activeSkus = shortages.stream()
                .map(FinishedGoodsService.InventoryShortage::productCode)
                .map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());

        for (FinishedGoodsService.InventoryShortage shortage : shortages) {
            String sku = shortage.productCode().trim().toUpperCase(Locale.ROOT);
            FactoryTask requirement = existingRequirementsBySku.get(sku);
            if (requirement == null) {
                requirement = new FactoryTask();
                requirement.setCompany(company);
                requirement.setSalesOrderId(order.getId());
            }
            requirement.setTitle(requirementTitle(sku));
            requirement.setDescription(String.format(
                    "Commercial proforma shortage for order #%s requires %s additional units of %s (%s).",
                    order.getOrderNumber(),
                    shortage.shortageQuantity(),
                    shortage.productName(),
                    sku
            ));
            requirement.setPackagingSlipId(null);
            requirement.setStatus("PENDING");
            requirement.setDueDate(resolveRequirementDueDate(today, shortage.shortageQuantity()));
            dirtyTasks.add(requirement);
        }

        for (FactoryTask task : existingRequirementsBySku.values()) {
            String sku = extractSku(task.getTitle());
            if (activeSkus.contains(sku) || isClosedRequirement(task)) {
                continue;
            }
            task.setStatus("CANCELLED");
            dirtyTasks.add(task);
        }

        if (!dirtyTasks.isEmpty()) {
            factoryTaskRepository.saveAll(dirtyTasks);
        }
    }

    private LocalDate resolveRequirementDueDate(LocalDate today, BigDecimal shortageQuantity) {
        if (shortageQuantity.compareTo(new BigDecimal("100")) >= 0) {
            return today.plusDays(1);
        }
        if (shortageQuantity.compareTo(new BigDecimal("50")) >= 0) {
            return today.plusDays(3);
        }
        return today.plusDays(7);
    }

    private boolean isManagedRequirement(FactoryTask task) {
        return task != null
                && task.getTitle() != null
                && task.getTitle().toUpperCase(Locale.ROOT).startsWith(REQUIREMENT_TITLE_PREFIX.toUpperCase(Locale.ROOT));
    }

    private boolean isClosedRequirement(FactoryTask task) {
        return task != null
                && CLOSED_REQUIREMENT_STATUSES.contains(task.getStatus() == null
                ? ""
                : task.getStatus().trim().toUpperCase(Locale.ROOT));
    }

    private String requirementTitle(String sku) {
        return REQUIREMENT_TITLE_PREFIX + sku;
    }

    private String extractSku(String title) {
        if (!StringUtils.hasText(title)) {
            return "UNKNOWN";
        }
        return title.substring(Math.min(title.length(), REQUIREMENT_TITLE_PREFIX.length())).trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    record CommercialAssessment(String commercialStatus,
                                List<FinishedGoodsService.InventoryShortage> shortages) {
    }
}
