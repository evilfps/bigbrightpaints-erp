package com.bigbrightpaints.erp.modules.sales.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingBucketDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingSummaryResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.OverdueInvoiceDto;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoicePdfService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;

@Service
public class DealerPortalService {

  private static final String PORTAL_AGING_BUCKETS = "0-0,1-30,31-60,61-90,91";
  private static final Set<String> PORTAL_ENABLED_DEALER_STATUSES =
      Set.of("ACTIVE", "ON_HOLD", "SUSPENDED", "BLOCKED");

  public record RequesterIdentity(Long userId, String email) {}

  private final DealerRepository dealerRepository;
  private final CompanyContextService companyContextService;
  private final DealerLedgerService dealerLedgerService;
  private final InvoiceRepository invoiceRepository;
  private final InvoicePdfService invoicePdfService;
  private final DealerService dealerService;
  private final SalesOrderRepository salesOrderRepository;
  private final CompanyClock companyClock;
  private final StatementService statementService;

  public DealerPortalService(
      DealerRepository dealerRepository,
      CompanyContextService companyContextService,
      DealerLedgerService dealerLedgerService,
      InvoiceRepository invoiceRepository,
      InvoicePdfService invoicePdfService,
      DealerService dealerService,
      SalesOrderRepository salesOrderRepository,
      CompanyClock companyClock,
      StatementService statementService) {
    this.dealerRepository = dealerRepository;
    this.companyContextService = companyContextService;
    this.dealerLedgerService = dealerLedgerService;
    this.invoiceRepository = invoiceRepository;
    this.invoicePdfService = invoicePdfService;
    this.dealerService = dealerService;
    this.salesOrderRepository = salesOrderRepository;
    this.companyClock = companyClock;
    this.statementService = statementService;
  }

  public Dealer getCurrentDealer() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) {
      throw new AccessDeniedException("No authenticated user");
    }
    Company company = companyContextService.requireCurrentCompany();
    UserAccount authenticatedUser = resolveAuthenticatedUser(auth);

    if (authenticatedUser != null && authenticatedUser.getId() != null) {
      Dealer matchedByUserId =
          resolveSingleDealerOrNull(
              dealerRepository.findAllByCompanyAndPortalUserId(company, authenticatedUser.getId()),
              "userId:" + authenticatedUser.getId());
      if (matchedByUserId != null) {
        return requireActivePortalDealer(matchedByUserId);
      }
    }

    String email = resolveAuthenticatedEmail(authenticatedUser, auth);
    Dealer matchedByEmail =
        resolveSingleDealerOrNull(
            dealerRepository.findAllByCompanyAndPortalUserEmailIgnoreCase(company, email),
            "email:" + email);
    if (matchedByEmail != null) {
      return requireActivePortalDealer(matchedByEmail);
    }
    throw new AccessDeniedException("Dealer mapping missing for authenticated principal");
  }

  public RequesterIdentity getCurrentRequesterIdentity() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) {
      throw new AccessDeniedException("No authenticated user");
    }
    UserAccount authenticatedUser = resolveAuthenticatedUser(auth);
    return new RequesterIdentity(
        authenticatedUser != null ? authenticatedUser.getId() : null,
        resolveAuthenticatedEmail(authenticatedUser, auth));
  }

  public boolean isDealerUser() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null) return false;
    return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_DEALER"));
  }

  public void verifyDealerAccess(Long dealerId) {
    if (!isDealerUser()) return;
    Dealer currentDealer = getCurrentDealer();
    if (!currentDealer.getId().equals(dealerId)) {
      throw new AccessDeniedException("Access denied: You can only view your own data");
    }
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getMyLedger() {
    Dealer dealer = getCurrentDealer();
    return normalizeLedgerView(dealerService.ledgerView(dealer.getId()));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getLedgerForDealer(Long dealerId) {
    verifyDealerAccess(dealerId);
    Dealer dealer = requireDealerForScopedRead(dealerId);
    return normalizeLedgerView(dealerService.ledgerView(dealer.getId()));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getMyInvoices() {
    Dealer dealer = getCurrentDealer();
    return buildInvoicesView(dealer);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getInvoicesForDealer(Long dealerId) {
    verifyDealerAccess(dealerId);
    Dealer dealer = requireDealerForScopedRead(dealerId);
    return buildInvoicesView(dealer);
  }

  private Map<String, Object> buildInvoicesView(Dealer dealer) {
    List<Invoice> invoices =
        invoiceRepository.findByCompanyAndDealerOrderByIssueDateDesc(dealer.getCompany(), dealer);

    List<Map<String, Object>> invoiceList = new ArrayList<>();
    BigDecimal totalOutstanding = BigDecimal.ZERO;

    for (Invoice inv : invoices) {
      Map<String, Object> invMap = new LinkedHashMap<>();
      invMap.put("id", inv.getId());
      invMap.put("invoiceNumber", inv.getInvoiceNumber());
      invMap.put("issueDate", inv.getIssueDate());
      invMap.put("dueDate", inv.getDueDate());
      invMap.put("totalAmount", inv.getTotalAmount());
      invMap.put("amount", inv.getTotalAmount());
      invMap.put("outstandingAmount", inv.getOutstandingAmount());
      invMap.put("status", inv.getStatus());
      invMap.put("currency", inv.getCurrency());
      invoiceList.add(invMap);

      if (inv.getOutstandingAmount() != null) {
        totalOutstanding = totalOutstanding.add(inv.getOutstandingAmount());
      }
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("dealerId", dealer.getId());
    result.put("dealerName", dealer.getName());
    result.put("totalOutstanding", totalOutstanding);
    result.put("invoiceCount", invoices.size());
    result.put("invoices", invoiceList);
    return result;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getMyOutstandingAndAging() {
    Dealer dealer = getCurrentDealer();
    return buildAgingView(dealer);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getAgingForDealer(Long dealerId) {
    verifyDealerAccess(dealerId);
    Dealer dealer = requireDealerForScopedRead(dealerId);
    return buildAgingView(dealer);
  }

  private Map<String, Object> buildAgingView(Dealer dealer) {
    LocalDate today = companyClock.today(dealer.getCompany());
    AgingSummaryResponse ledgerAging =
        statementService.dealerAging(dealer, today, PORTAL_AGING_BUCKETS);
    BigDecimal totalOutstanding =
        ledgerAging.totalOutstanding() != null ? ledgerAging.totalOutstanding() : BigDecimal.ZERO;
    BigDecimal creditOutstanding = totalOutstanding.max(BigDecimal.ZERO);
    Map<String, Object> agingBuckets = toPortalAgingBuckets(ledgerAging);
    List<Map<String, Object>> overdueInvoices =
        toOverdueInvoicePayload(statementService.dealerOverdueInvoices(dealer, today));

    long pendingOrderCount = resolvePendingOrderCount(dealer, null);
    BigDecimal pendingOrderExposure = resolvePendingOrderExposure(dealer, null);
    BigDecimal creditUsed = creditOutstanding.add(pendingOrderExposure);
    BigDecimal creditLimit =
        dealer.getCreditLimit() != null ? dealer.getCreditLimit() : BigDecimal.ZERO;
    BigDecimal availableCredit = creditLimit.subtract(creditUsed);
    if (availableCredit.compareTo(BigDecimal.ZERO) < 0) {
      availableCredit = BigDecimal.ZERO;
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("dealerId", dealer.getId());
    result.put("dealerName", dealer.getName());
    result.put("creditLimit", creditLimit);
    result.put("totalOutstanding", totalOutstanding);
    result.put("pendingOrderCount", pendingOrderCount);
    result.put("pendingOrderExposure", pendingOrderExposure);
    result.put("creditUsed", creditUsed);
    result.put("availableCredit", availableCredit);
    result.put("agingBuckets", agingBuckets);
    result.put(
        "buckets",
        Map.of(
            "current", agingBuckets.getOrDefault("current", BigDecimal.ZERO),
            "30days", agingBuckets.getOrDefault("30days", BigDecimal.ZERO),
            "60days", agingBuckets.getOrDefault("60days", BigDecimal.ZERO),
            "90days", agingBuckets.getOrDefault("90days", BigDecimal.ZERO),
            "over90", agingBuckets.getOrDefault("over90", BigDecimal.ZERO)));
    result.put("overdueInvoices", overdueInvoices);
    return result;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getMyDashboard() {
    Dealer dealer = getCurrentDealer();
    LocalDate today = companyClock.today(dealer.getCompany());

    BigDecimal currentBalance = dealerLedgerService.currentBalance(dealer.getId());
    Map<String, Object> aging = buildAgingView(dealer);
    aging.put(
        "creditStatus",
        resolveCreditStatus(
            dealer.getCreditLimit() != null ? dealer.getCreditLimit() : BigDecimal.ZERO,
            (BigDecimal) aging.get("creditUsed")));

    long pendingInvoices = statementService.dealerOpenInvoiceCount(dealer, today);
    long pendingOrderCount = ((Number) aging.get("pendingOrderCount")).longValue();
    BigDecimal pendingOrderExposure = (BigDecimal) aging.get("pendingOrderExposure");
    List<SalesOrder> dealerOrders =
        salesOrderRepository.findByCompanyAndDealerOrderByCreatedAtDesc(
            dealer.getCompany(), dealer);
    long orderCount = dealerOrders != null ? dealerOrders.size() : 0L;

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("dealerId", dealer.getId());
    result.put("dealerName", dealer.getName());
    result.put("dealerCode", dealer.getCode());
    result.put("currentBalance", currentBalance);
    result.put("creditLimit", dealer.getCreditLimit());
    result.put("availableCredit", aging.get("availableCredit"));
    result.put("totalOutstanding", aging.get("totalOutstanding"));
    result.put("pendingInvoices", pendingInvoices);
    result.put("orderCount", orderCount);
    result.put("pendingOrderCount", pendingOrderCount);
    result.put("pendingOrderExposure", pendingOrderExposure);
    result.put("creditUsed", aging.get("creditUsed"));
    result.put("creditStatus", aging.get("creditStatus"));
    result.put("agingBuckets", aging.get("agingBuckets"));
    result.put("outstandingBalance", aging.get("totalOutstanding"));
    result.put("creditAvailable", aging.get("availableCredit"));
    return result;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getMyOrders() {
    Dealer dealer = getCurrentDealer();
    List<SalesOrder> orders =
        salesOrderRepository.findByCompanyAndDealerOrderByCreatedAtDesc(
            dealer.getCompany(), dealer);
    Set<Long> activeInvoicedOrderIds =
        invoiceRepository.findActiveSalesOrderIdsByCompanyAndDealer(dealer.getCompany(), dealer);

    List<Map<String, Object>> orderList = new ArrayList<>();
    long pendingOrderCount = 0L;
    BigDecimal pendingOrderExposure = BigDecimal.ZERO;
    for (SalesOrder order : orders) {
      boolean pendingCreditExposure =
          contributesPendingCreditExposure(order, activeInvoicedOrderIds);
      Map<String, Object> orderMap = new LinkedHashMap<>();
      orderMap.put("id", order.getId());
      orderMap.put("orderId", order.getId());
      orderMap.put("orderNumber", order.getOrderNumber());
      orderMap.put("status", order.getStatus());
      orderMap.put("totalAmount", order.getTotalAmount());
      orderMap.put("createdAt", order.getCreatedAt());
      orderMap.put("orderDate", order.getCreatedAt());
      orderMap.put("notes", order.getNotes());
      orderMap.put("pendingCreditExposure", pendingCreditExposure);
      orderList.add(orderMap);
      if (pendingCreditExposure) {
        pendingOrderCount++;
        pendingOrderExposure =
            pendingOrderExposure.add(
                order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO);
      }
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("dealerId", dealer.getId());
    result.put("dealerName", dealer.getName());
    result.put("orderCount", orders.size());
    result.put("pendingOrderCount", pendingOrderCount);
    result.put("pendingOrderExposure", pendingOrderExposure);
    result.put("orders", orderList);
    return result;
  }

  @Transactional(readOnly = true)
  public InvoicePdfService.PdfDocument getMyInvoicePdf(Long invoiceId) {
    Dealer dealer = getCurrentDealer();
    Invoice invoice =
        invoiceRepository
            .findByCompanyAndId(dealer.getCompany(), invoiceId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));

    if (invoice.getDealer() == null
        || invoice.getDealer().getId() == null
        || invoice.getCompany() == null
        || dealer.getId() == null
        || dealer.getCompany() == null
        || dealer.getCompany().getId() == null
        || invoice.getCompany().getId() == null
        || !dealer.getCompany().getId().equals(invoice.getCompany().getId())
        || !dealer.getId().equals(invoice.getDealer().getId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found");
    }

    return invoicePdfService.renderInvoicePdf(invoiceId);
  }

  private UserAccount resolveAuthenticatedUser(Authentication auth) {
    Object principal = auth.getPrincipal();
    if (principal instanceof UserPrincipal userPrincipal) {
      return userPrincipal.getUser();
    }
    return null;
  }

  private String resolveAuthenticatedEmail(UserAccount authenticatedUser, Authentication auth) {
    String email = authenticatedUser == null ? null : authenticatedUser.getEmail();
    if (!StringUtils.hasText(email)) {
      email = auth == null ? null : auth.getName();
    }
    if (!StringUtils.hasText(email)) {
      throw new AccessDeniedException("No authenticated user identity");
    }
    return email.trim();
  }

  private Dealer resolveSingleDealerOrNull(List<Dealer> candidates, String identity) {
    if (candidates == null || candidates.isEmpty()) {
      return null;
    }
    if (candidates.size() > 1) {
      throw new AccessDeniedException("Ambiguous dealer mapping for " + identity);
    }
    return candidates.get(0);
  }

  private Dealer requireDealerForScopedRead(Long dealerId) {
    Company company = companyContextService.requireCurrentCompany();
    return dealerRepository
        .findByCompanyAndId(company, dealerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dealer not found"));
  }

  private Dealer requireActivePortalDealer(Dealer dealer) {
    if (dealer == null) {
      throw new AccessDeniedException("Dealer mapping missing for authenticated principal");
    }
    String status = dealer.getStatus();
    if (status != null
        && PORTAL_ENABLED_DEALER_STATUSES.contains(status.trim().toUpperCase(Locale.ROOT))) {
      return dealer;
    }
    throw new AccessDeniedException("Dealer portal access is disabled for inactive dealer mapping");
  }

  private BigDecimal resolvePendingOrderExposure(Dealer dealer, Long excludeOrderId) {
    BigDecimal exposure =
        salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(
            dealer.getCompany(),
            dealer,
            SalesOrderCreditExposurePolicy.pendingCreditExposureStatuses(),
            excludeOrderId);
    return exposure != null ? exposure : BigDecimal.ZERO;
  }

  private long resolvePendingOrderCount(Dealer dealer, Long excludeOrderId) {
    return salesOrderRepository.countPendingCreditExposureByCompanyAndDealer(
        dealer.getCompany(),
        dealer,
        SalesOrderCreditExposurePolicy.pendingCreditExposureStatuses(),
        excludeOrderId);
  }

  private boolean contributesPendingCreditExposure(
      SalesOrder order, Set<Long> activeInvoicedOrderIds) {
    return order != null
        && (activeInvoicedOrderIds == null || !activeInvoicedOrderIds.contains(order.getId()))
        && !"CASH".equalsIgnoreCase(order.getPaymentMode())
        && SalesOrderCreditExposurePolicy.isPendingCreditExposureStatus(order.getStatus());
  }

  private String resolveCreditStatus(BigDecimal creditLimit, BigDecimal creditUsed) {
    BigDecimal safeLimit = creditLimit != null ? creditLimit : BigDecimal.ZERO;
    BigDecimal safeUsed = creditUsed != null ? creditUsed : BigDecimal.ZERO;
    if (safeLimit.compareTo(BigDecimal.ZERO) <= 0) {
      return safeUsed.compareTo(BigDecimal.ZERO) > 0 ? "OVER_LIMIT" : "WITHIN_LIMIT";
    }
    BigDecimal ratio = safeUsed.divide(safeLimit, 4, java.math.RoundingMode.HALF_UP);
    if (ratio.compareTo(BigDecimal.ONE) >= 0) {
      return "OVER_LIMIT";
    }
    if (ratio.compareTo(new BigDecimal("0.80")) >= 0) {
      return "NEAR_LIMIT";
    }
    return "WITHIN_LIMIT";
  }

  private Map<String, Object> toPortalAgingBuckets(AgingSummaryResponse aging) {
    Map<String, Object> buckets = new LinkedHashMap<>();
    buckets.put("current", BigDecimal.ZERO);
    buckets.put("1-30 days", BigDecimal.ZERO);
    buckets.put("31-60 days", BigDecimal.ZERO);
    buckets.put("61-90 days", BigDecimal.ZERO);
    buckets.put("90+ days", BigDecimal.ZERO);
    buckets.put("30days", BigDecimal.ZERO);
    buckets.put("60days", BigDecimal.ZERO);
    buckets.put("90days", BigDecimal.ZERO);
    buckets.put("over90", BigDecimal.ZERO);
    if (aging == null || aging.buckets() == null) {
      return buckets;
    }
    for (AgingBucketDto bucket : aging.buckets()) {
      if (bucket == null || bucket.amount() == null) {
        continue;
      }
      String key = resolvePortalAgingBucketKey(bucket);
      if (key != null) {
        buckets.put(key, bucket.amount());
        if ("1-30 days".equals(key)) {
          buckets.put("30days", bucket.amount());
        } else if ("31-60 days".equals(key)) {
          buckets.put("60days", bucket.amount());
        } else if ("61-90 days".equals(key)) {
          buckets.put("90days", bucket.amount());
        } else if ("90+ days".equals(key)) {
          buckets.put("over90", bucket.amount());
        }
      }
    }
    return buckets;
  }

  private Map<String, Object> normalizeLedgerView(Map<String, Object> ledgerView) {
    if (ledgerView == null || ledgerView.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> normalized = new LinkedHashMap<>(ledgerView);
    Object entriesCandidate = ledgerView.get("entries");
    if (!(entriesCandidate instanceof List<?> entries)) {
      return normalized;
    }
    List<Map<String, Object>> ledgerEntries = new ArrayList<>();
    for (Object entryCandidate : entries) {
      if (!(entryCandidate instanceof Map<?, ?> entry)) {
        continue;
      }
      Map<String, Object> normalizedEntry = new LinkedHashMap<>();
      normalizedEntry.put("date", entry.get("date"));
      Object memo = entry.get("memo");
      Object reference = entry.get("reference");
      String memoText = memo != null ? String.valueOf(memo).trim() : null;
      normalizedEntry.put("description", StringUtils.hasText(memoText) ? memoText : reference);
      normalizedEntry.put("debit", entry.get("debit"));
      normalizedEntry.put("credit", entry.get("credit"));
      normalizedEntry.put("balance", entry.get("runningBalance"));
      ledgerEntries.add(normalizedEntry);
    }
    normalized.put("ledgerEntries", ledgerEntries);
    return normalized;
  }

  private String resolvePortalAgingBucketKey(AgingBucketDto bucket) {
    if (bucket == null || "Credit Balance".equals(bucket.label())) {
      return null;
    }
    if (bucket.fromDays() == 0 && Integer.valueOf(0).equals(bucket.toDays())) {
      return "current";
    }
    if (bucket.fromDays() == 1 && Integer.valueOf(30).equals(bucket.toDays())) {
      return "1-30 days";
    }
    if (bucket.fromDays() == 31 && Integer.valueOf(60).equals(bucket.toDays())) {
      return "31-60 days";
    }
    if (bucket.fromDays() == 61 && Integer.valueOf(90).equals(bucket.toDays())) {
      return "61-90 days";
    }
    if (bucket.fromDays() == 91 && bucket.toDays() == null) {
      return "90+ days";
    }
    return null;
  }

  private List<Map<String, Object>> toOverdueInvoicePayload(
      List<OverdueInvoiceDto> overdueInvoices) {
    if (overdueInvoices == null || overdueInvoices.isEmpty()) {
      return List.of();
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (OverdueInvoiceDto overdueInvoice : overdueInvoices) {
      if (overdueInvoice == null) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("invoiceNumber", overdueInvoice.invoiceNumber());
      row.put("issueDate", overdueInvoice.issueDate());
      row.put("dueDate", overdueInvoice.dueDate());
      row.put("daysOverdue", overdueInvoice.daysOverdue());
      row.put("outstandingAmount", overdueInvoice.outstandingAmount());
      rows.add(row);
    }
    return rows;
  }
}
