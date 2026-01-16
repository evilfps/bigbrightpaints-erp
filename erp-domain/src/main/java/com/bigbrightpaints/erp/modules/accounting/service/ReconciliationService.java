package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerBalanceView;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierBalanceView;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReservation;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReservationRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for reconciling GL accounts with sub-ledgers.
 * Used to detect discrepancies between AR/AP accounts and dealer/supplier ledgers.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final CompanyContextService companyContextService;
    private final AccountRepository accountRepository;
    private final DealerRepository dealerRepository;
    private final DealerLedgerRepository dealerLedgerRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierLedgerRepository supplierLedgerRepository;
    private final InventoryReservationRepository inventoryReservationRepository;
    private final PackagingSlipRepository packagingSlipRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final JournalEntryRepository journalEntryRepository;

    public ReconciliationService(CompanyContextService companyContextService,
                                  AccountRepository accountRepository,
                                  DealerRepository dealerRepository,
                                  DealerLedgerRepository dealerLedgerRepository,
                                  SupplierRepository supplierRepository,
                                  SupplierLedgerRepository supplierLedgerRepository,
                                  InventoryReservationRepository inventoryReservationRepository,
                                  PackagingSlipRepository packagingSlipRepository,
                                  SalesOrderRepository salesOrderRepository,
                                  JournalEntryRepository journalEntryRepository) {
        this.companyContextService = companyContextService;
        this.accountRepository = accountRepository;
        this.dealerRepository = dealerRepository;
        this.dealerLedgerRepository = dealerLedgerRepository;
        this.supplierRepository = supplierRepository;
        this.supplierLedgerRepository = supplierLedgerRepository;
        this.inventoryReservationRepository = inventoryReservationRepository;
        this.packagingSlipRepository = packagingSlipRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.journalEntryRepository = journalEntryRepository;
    }

    /**
     * Reconcile AR GL account balance with sum of dealer ledger balances.
     * Returns discrepancies if any.
     */
    @Transactional(readOnly = true)
    public ReconciliationResult reconcileArWithDealerLedger() {
        Company company = companyContextService.requireCurrentCompany();
        
        // Get all AR accounts
        List<Account> arAccounts = accountRepository.findByCompanyOrderByCodeAsc(company).stream()
                .filter(a -> a.getType() == AccountType.ASSET)
                .filter(a -> a.getCode() != null &&
                        (a.getCode().toUpperCase().contains("AR") ||
                                a.getCode().toUpperCase().contains("RECEIVABLE")))
                .toList();

        BigDecimal totalArBalance = arAccounts.stream()
                .map(Account::getBalance)
                .filter(b -> b != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Get all dealers with their ledger balances
        List<Dealer> dealers = dealerRepository.findByCompanyOrderByNameAsc(company);
        List<Long> dealerIds = dealers.stream().map(Dealer::getId).toList();
        
        Map<Long, BigDecimal> dealerBalances = dealerLedgerRepository
                .aggregateBalances(company, dealerIds)
                .stream()
                .collect(Collectors.toMap(DealerBalanceView::dealerId, DealerBalanceView::balance));

        BigDecimal totalDealerLedgerBalance = dealerBalances.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal variance = totalArBalance.subtract(totalDealerLedgerBalance);
        boolean isReconciled = variance.abs().compareTo(TOLERANCE) <= 0;

        List<DealerDiscrepancy> discrepancies = new ArrayList<>();
        
        // Check individual dealers
        for (Dealer dealer : dealers) {
            BigDecimal ledgerBalance = dealerBalances.getOrDefault(dealer.getId(), BigDecimal.ZERO);
            BigDecimal outstandingBalance = dealer.getOutstandingBalance() != null 
                    ? dealer.getOutstandingBalance() 
                    : BigDecimal.ZERO;
            
            BigDecimal dealerVariance = outstandingBalance.subtract(ledgerBalance);
            if (dealerVariance.abs().compareTo(TOLERANCE) > 0) {
                discrepancies.add(new DealerDiscrepancy(
                        dealer.getId(),
                        dealer.getCode(),
                        dealer.getName(),
                        outstandingBalance,
                        ledgerBalance,
                        dealerVariance
                ));
            }
        }

        log.info("AR Reconciliation: GL={}, DealerLedger={}, Variance={}, Reconciled={}",
                totalArBalance, totalDealerLedgerBalance, variance, isReconciled);

        return new ReconciliationResult(
                totalArBalance,
                totalDealerLedgerBalance,
                variance,
                isReconciled,
                discrepancies,
                arAccounts.size(),
                dealers.size()
        );
    }

    /**
     * Reconcile AP GL account balance with sum of supplier ledger balances.
     */
    @Transactional(readOnly = true)
    public SupplierReconciliationResult reconcileApWithSupplierLedger() {
        Company company = companyContextService.requireCurrentCompany();

        List<Account> apAccounts = accountRepository.findByCompanyOrderByCodeAsc(company).stream()
                .filter(a -> a.getType() == AccountType.LIABILITY)
                .filter(a -> a.getCode() != null &&
                        (a.getCode().toUpperCase().contains("AP") ||
                                a.getCode().toUpperCase().contains("PAYABLE")))
                .toList();

        BigDecimal totalApBalance = apAccounts.stream()
                .map(account -> normalizeBalance(account.getType(), account.getBalance()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Supplier> suppliers = supplierRepository.findByCompanyOrderByNameAsc(company);
        List<Long> supplierIds = suppliers.stream().map(Supplier::getId).toList();

        Map<Long, BigDecimal> supplierBalances = supplierLedgerRepository
                .aggregateBalances(company, supplierIds)
                .stream()
                .collect(Collectors.toMap(SupplierBalanceView::supplierId, SupplierBalanceView::balance));

        BigDecimal totalSupplierLedgerBalance = supplierBalances.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal variance = totalApBalance.subtract(totalSupplierLedgerBalance);
        boolean isReconciled = variance.abs().compareTo(TOLERANCE) <= 0;

        List<SupplierDiscrepancy> discrepancies = new ArrayList<>();

        for (Supplier supplier : suppliers) {
            BigDecimal ledgerBalance = supplierBalances.getOrDefault(supplier.getId(), BigDecimal.ZERO);
            BigDecimal outstandingBalance = supplier.getOutstandingBalance() != null
                    ? supplier.getOutstandingBalance()
                    : BigDecimal.ZERO;

            BigDecimal supplierVariance = outstandingBalance.subtract(ledgerBalance);
            if (supplierVariance.abs().compareTo(TOLERANCE) > 0) {
                discrepancies.add(new SupplierDiscrepancy(
                        supplier.getId(),
                        supplier.getCode(),
                        supplier.getName(),
                        outstandingBalance,
                        ledgerBalance,
                        supplierVariance
                ));
            }
        }

        log.info("AP Reconciliation: GL={}, SupplierLedger={}, Variance={}, Reconciled={}",
                totalApBalance, totalSupplierLedgerBalance, variance, isReconciled);

        return new SupplierReconciliationResult(
                totalApBalance,
                totalSupplierLedgerBalance,
                variance,
                isReconciled,
                discrepancies,
                apAccounts.size(),
                suppliers.size()
        );
    }

    private BigDecimal normalizeBalance(AccountType type, BigDecimal balance) {
        BigDecimal safeBalance = balance == null ? BigDecimal.ZERO : balance;
        boolean debitNormal = type == null || type.isDebitNormalBalance();
        return debitNormal ? safeBalance : safeBalance.negate();
    }

    /**
     * Check for orphan reservations (RESERVED status without corresponding packaging slips)
     * These could block dispatch and should be cleaned up or regenerated.
     */
    @Transactional(readOnly = true)
    public OrphanReservationReport findOrphanReservations() {
        Company company = companyContextService.requireCurrentCompany();
        
        List<InventoryReservation> reservedItems = inventoryReservationRepository
                .findByFinishedGoodCompanyAndStatus(company, "RESERVED");
        
        List<OrphanReservation> orphans = new ArrayList<>();
        
        for (InventoryReservation reservation : reservedItems) {
            String refType = reservation.getReferenceType();
            String refId = reservation.getReferenceId();
            
            // Only check SALES_ORDER type reservations
            if (!"SALES_ORDER".equals(refType)) {
                continue;
            }
            
            // Check if there's a corresponding packaging slip
            Long orderId;
            try {
                orderId = Long.parseLong(refId);
            } catch (NumberFormatException ex) {
                log.warn("Skipping orphan reservation {} with non-numeric reference {}", reservation.getId(), refId);
                continue;
            }
            List<PackagingSlip> slips = packagingSlipRepository
                    .findAllByCompanyAndSalesOrderId(company, orderId);
            
            if (slips.isEmpty()) {
                // No slip = orphan reservation
                Optional<SalesOrder> order = salesOrderRepository.findByCompanyAndId(company, orderId);
                orphans.add(new OrphanReservation(
                        reservation.getId(),
                        orderId,
                        order.map(SalesOrder::getOrderNumber).orElse("UNKNOWN-" + refId),
                        reservation.getReservedQuantity() != null 
                                ? reservation.getReservedQuantity() 
                                : reservation.getQuantity()
                ));
            }
        }

        log.info("Orphan reservation check for company {}: found {} orphans",
                company.getCode(), orphans.size());
        
        return new OrphanReservationReport(orphans.size(), orphans);
    }

    /**
     * Clean up orphan reservations by setting their status to CANCELLED.
     * Returns count of cleaned reservations.
     */
    @Transactional
    public int cleanOrphanReservations() {
        Company company = companyContextService.requireCurrentCompany();
        OrphanReservationReport report = findOrphanReservations();
        int cleaned = 0;
        
        for (OrphanReservation orphan : report.orphans()) {
            Optional<InventoryReservation> opt = inventoryReservationRepository
                    .findByFinishedGoodCompanyAndId(company, orphan.reservationId());
            if (opt.isPresent()) {
                InventoryReservation reservation = opt.get();
                reservation.setStatus("CANCELLED");
                reservation.setReservedQuantity(BigDecimal.ZERO);
                inventoryReservationRepository.save(reservation);
                cleaned++;
                log.info("Cleaned orphan reservation {} for order {}", orphan.reservationId(), orphan.orderNumber());
            }
        }
        
        return cleaned;
    }

    /**
     * Check for potential reference collisions with new idempotency pattern.
     * Detects SALE-{id} and *-COGS-* references that could conflict with new flow.
     */
    @Transactional(readOnly = true)
    public ReferenceCollisionReport checkReferenceCollisions() {
        Company company = companyContextService.requireCurrentCompany();
        
        List<JournalEntry> allEntries = journalEntryRepository.findByCompanyOrderByEntryDateDesc(company);
        
        int saleCount = 0;
        int cogsCount = 0;
        List<String> potentialCollisions = new ArrayList<>();
        
        for (JournalEntry entry : allEntries) {
            String ref = entry.getReferenceNumber();
            if (ref == null) continue;
            
            // Check for SALE-{number} pattern
            if (ref.matches("^SALE-\\d+$")) {
                saleCount++;
                potentialCollisions.add(ref);
            }
            
            // Check for *-COGS-* pattern
            if (ref.contains("-COGS-")) {
                cogsCount++;
                potentialCollisions.add(ref);
            }
        }

        log.info("Reference collision check for company {}: {} SALE-*, {} *-COGS-*",
                company.getCode(), saleCount, cogsCount);
        
        return new ReferenceCollisionReport(saleCount, cogsCount, potentialCollisions);
    }

    // Result DTOs
    public record ReconciliationResult(
            BigDecimal glArBalance,
            BigDecimal dealerLedgerTotal,
            BigDecimal variance,
            boolean isReconciled,
            List<DealerDiscrepancy> discrepancies,
            int arAccountCount,
            int dealerCount
    ) {}

    public record DealerDiscrepancy(
            Long dealerId,
            String dealerCode,
            String dealerName,
            BigDecimal outstandingBalance,
            BigDecimal ledgerBalance,
            BigDecimal variance
    ) {}

    public record OrphanReservationReport(
            int orphanCount,
            List<OrphanReservation> orphans
    ) {}

    public record OrphanReservation(
            Long reservationId,
            Long orderId,
            String orderNumber,
            BigDecimal quantity
    ) {}

    public record ReferenceCollisionReport(
            int salePatternCount,
            int cogsPatternCount,
            List<String> potentialCollisions
    ) {}

    public record SupplierReconciliationResult(
            BigDecimal glApBalance,
            BigDecimal supplierLedgerTotal,
            BigDecimal variance,
            boolean isReconciled,
            List<SupplierDiscrepancy> discrepancies,
            int apAccountCount,
            int supplierCount
    ) {}

    public record SupplierDiscrepancy(
            Long supplierId,
            String supplierCode,
            String supplierName,
            BigDecimal outstandingBalance,
            BigDecimal ledgerBalance,
            BigDecimal variance
    ) {}
}
