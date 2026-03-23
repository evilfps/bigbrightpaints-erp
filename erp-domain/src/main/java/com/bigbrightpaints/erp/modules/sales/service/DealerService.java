package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.GstRegistrationType;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingBucketDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingSummaryResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.OverdueInvoiceDto;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerPaymentTerms;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.dto.CreateDealerRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DealerLookupResponse;
import com.bigbrightpaints.erp.modules.sales.dto.DealerResponse;
import com.bigbrightpaints.erp.modules.sales.util.DealerProvisioningSupport;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
public class DealerService {

    private static final Logger log = LoggerFactory.getLogger(DealerService.class);
    private static final int DEALER_SEARCH_LIMIT = 10;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String DIGITS = "0123456789";
    private static final String SPECIAL = "!@#$%^&*";
    private static final String ALL = LOWER + UPPER + DIGITS + SPECIAL;
    private static final Pattern GSTIN_PATTERN = Pattern.compile("^[0-9]{2}[A-Z0-9]{13}$");
    private static final String PORTAL_AGING_BUCKETS = "0-0,1-30,31-60,61-90,91";

    private final DealerRepository dealerRepository;
    private final CompanyContextService companyContextService;
    private final UserAccountRepository userAccountRepository;
    private final RoleService roleService;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final AccountRepository accountRepository;
    private final DealerLedgerService dealerLedgerService;
    private final StatementService statementService;
    private final SalesOrderRepository salesOrderRepository;
    private final CompanyClock companyClock;

    public DealerService(DealerRepository dealerRepository,
                         CompanyContextService companyContextService,
                         UserAccountRepository userAccountRepository,
                         RoleService roleService,
                         PasswordEncoder passwordEncoder,
                         EmailService emailService,
                         AccountRepository accountRepository,
                         DealerLedgerService dealerLedgerService,
                         StatementService statementService,
                         SalesOrderRepository salesOrderRepository,
                         CompanyClock companyClock) {
        this.dealerRepository = dealerRepository;
        this.companyContextService = companyContextService;
        this.userAccountRepository = userAccountRepository;
        this.roleService = roleService;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.accountRepository = accountRepository;
        this.dealerLedgerService = dealerLedgerService;
        this.statementService = statementService;
        this.salesOrderRepository = salesOrderRepository;
        this.companyClock = companyClock;
    }

    @Transactional
    public DealerResponse createDealer(CreateDealerRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        String contactEmail = request.contactEmail().trim();
        if (!dealerRepository.findAllByCompanyAndPortalUserEmailIgnoreCase(company, contactEmail).isEmpty()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Dealer already exists for this portal user");
        }

        Dealer dealer = dealerRepository.findByCompanyAndEmailIgnoreCase(company, contactEmail)
                .orElseGet(() -> {
                    Dealer fresh = new Dealer();
                    fresh.setCompany(company);
                    fresh.setCode(DealerProvisioningSupport.generateDealerCode(request.name(), company, dealerRepository));
                    return fresh;
                });
        dealer.setName(request.name().trim());
        dealer.setCompanyName(request.companyName().trim());
        dealer.setEmail(contactEmail);
        dealer.setPhone(request.contactPhone().trim());
        dealer.setAddress(request.address());
        dealer.setGstNumber(normalizeGstNumber(request.gstNumber()));
        dealer.setStateCode(normalizeStateCode(request.stateCode()));
        dealer.setGstRegistrationType(resolveRegistrationType(request.gstRegistrationType()));
        dealer.setPaymentTerms(resolvePaymentTerms(request.paymentTerms()));
        dealer.setRegion(normalizeRegion(request.region()));
        dealer.setStatus(DealerProvisioningSupport.ACTIVE_STATUS);
        if (request.creditLimit() != null) {
            dealer.setCreditLimit(request.creditLimit());
        }
        if (!StringUtils.hasText(dealer.getCode())) {
            dealer.setCode(DealerProvisioningSupport.generateDealerCode(request.name(), company, dealerRepository));
        }

        dealer = dealerRepository.save(dealer);

        String rawPassword = null;
        UserAccount portalUser = userAccountRepository.findByEmailIgnoreCase(contactEmail).orElse(null);
        if (portalUser == null) {
            rawPassword = generateRandomPassword();
            portalUser = new UserAccount(contactEmail, passwordEncoder.encode(rawPassword), dealer.getName());
            portalUser.setMustChangePassword(true);
        }
        portalUser.getRoles().add(roleService.ensureRoleExists("ROLE_DEALER"));
        portalUser.getCompanies().add(company);
        portalUser = userAccountRepository.save(portalUser);

        Account receivableAccount = dealer.getReceivableAccount();
        if (receivableAccount == null) {
            receivableAccount = DealerProvisioningSupport.createReceivableAccount(company, dealer, accountRepository);
        } else if (!receivableAccount.isActive()) {
            receivableAccount.setActive(true);
            receivableAccount = accountRepository.save(receivableAccount);
        }

        dealer.setPortalUser(portalUser);
        dealer.setReceivableAccount(receivableAccount);
        dealer = dealerRepository.save(dealer);

        scheduleCredentialEmailAfterCommit(contactEmail, dealer.getName(), rawPassword);
        return toResponse(dealer, portalUser.getEmail());
    }

    @Transactional
    public List<DealerResponse> listDealers() {
        Company company = companyContextService.requireCurrentCompany();
        List<Dealer> dealers = dealerRepository.findByCompanyAndStatusIgnoreCaseOrderByNameAsc(
                company, DealerProvisioningSupport.ACTIVE_STATUS);
        List<Long> dealerIds = dealers.stream().map(Dealer::getId).toList();
        var balances = dealerLedgerService.currentBalances(dealerIds);
        return dealers.stream()
                .map(dealer -> toResponse(
                        dealer,
                        dealer.getPortalUser() != null ? dealer.getPortalUser().getEmail() : null,
                        balances.getOrDefault(dealer.getId(), BigDecimal.ZERO)))
                .toList();
    }

    @Transactional
    public List<DealerLookupResponse> search(String query, String status, String region, String creditStatus) {
        Company company = companyContextService.requireCurrentCompany();
        String term = StringUtils.hasText(query) ? query.trim() : "";
        String normalizedStatus = normalizeOptionalToken(status);
        String normalizedRegion = normalizeOptionalToken(region);
        String normalizedCreditStatus = normalizeCreditStatus(creditStatus);

        List<Dealer> matches = dealerRepository.searchFiltered(
                company,
                term,
                normalizedStatus,
                normalizedRegion,
                PageRequest.of(0, DEALER_SEARCH_LIMIT));

        List<Long> dealerIds = matches.stream().map(Dealer::getId).toList();
        var balances = dealerLedgerService.currentBalances(dealerIds);

        List<DealerLookupResponse> resolved = new ArrayList<>();
        for (Dealer dealer : matches) {
            BigDecimal outstandingBalance = balances.getOrDefault(dealer.getId(), BigDecimal.ZERO);
            BigDecimal pendingOrderExposure = resolvePendingOrderExposure(dealer);
            String dealerCreditStatus = resolveCreditStatus(dealer, outstandingBalance, pendingOrderExposure);
            if (normalizedCreditStatus != null && !normalizedCreditStatus.equals(dealerCreditStatus)) {
                continue;
            }

            Account receivableAccount = dealer.getReceivableAccount();
            resolved.add(new DealerLookupResponse(
                    dealer.getId(),
                    dealer.getPublicId(),
                    dealer.getName(),
                    dealer.getCode(),
                    outstandingBalance,
                    dealer.getCreditLimit(),
                    receivableAccount != null ? receivableAccount.getId() : null,
                    receivableAccount != null ? receivableAccount.getCode() : null,
                    dealer.getStateCode(),
                    dealer.getGstRegistrationType(),
                    dealer.getPaymentTerms(),
                    dealer.getRegion(),
                    dealerCreditStatus
            ));
        }
        return resolved;
    }

    @Transactional
    public DealerResponse updateDealer(Long dealerId, CreateDealerRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = dealerRepository.findByCompanyAndId(company, dealerId)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Dealer not found"));

        if (request.name() != null && !request.name().isBlank()) {
            dealer.setName(request.name().trim());
        }
        if (request.companyName() != null && !request.companyName().isBlank()) {
            dealer.setCompanyName(request.companyName().trim());
        }
        if (request.contactEmail() != null && !request.contactEmail().isBlank()) {
            dealer.setEmail(request.contactEmail().trim());
        }
        if (request.contactPhone() != null && !request.contactPhone().isBlank()) {
            dealer.setPhone(request.contactPhone().trim());
        }
        if (request.address() != null) {
            dealer.setAddress(request.address());
        }
        dealer.setGstNumber(normalizeGstNumber(request.gstNumber()));
        dealer.setStateCode(normalizeStateCode(request.stateCode()));
        dealer.setGstRegistrationType(resolveRegistrationType(request.gstRegistrationType()));
        dealer.setPaymentTerms(resolvePaymentTerms(request.paymentTerms()));
        dealer.setRegion(normalizeRegion(request.region()));
        if (request.creditLimit() != null) {
            dealer.setCreditLimit(request.creditLimit());
        }

        dealer = dealerRepository.save(dealer);
        BigDecimal balance = dealerLedgerService.currentBalance(dealer.getId());
        return toResponse(dealer,
                dealer.getPortalUser() != null ? dealer.getPortalUser().getEmail() : null,
                balance);
    }

    @Transactional
    public Map<String, Object> creditUtilization(Long dealerId) {
        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = requireDealerForRead(company, dealerId);

        BigDecimal outstanding = dealerLedgerService.currentBalance(dealerId);
        BigDecimal creditOutstanding = outstanding.max(BigDecimal.ZERO);
        BigDecimal pendingOrderExposure = resolvePendingOrderExposure(dealer);
        BigDecimal creditLimit = dealer.getCreditLimit() != null ? dealer.getCreditLimit() : BigDecimal.ZERO;
        BigDecimal creditUsed = creditOutstanding.add(pendingOrderExposure);
        BigDecimal availableCredit = creditLimit.subtract(creditUsed);
        if (availableCredit.compareTo(BigDecimal.ZERO) < 0) {
            availableCredit = BigDecimal.ZERO;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dealerId", dealer.getId());
        payload.put("dealerName", dealer.getName());
        payload.put("creditLimit", creditLimit);
        payload.put("outstandingAmount", outstanding);
        payload.put("pendingOrderExposure", pendingOrderExposure);
        payload.put("creditUsed", creditUsed);
        payload.put("availableCredit", availableCredit);
        payload.put("creditStatus", resolveCreditStatus(dealer, outstanding, pendingOrderExposure));
        return payload;
    }

    @Transactional
    public Map<String, Object> agingSummary(Long dealerId) {
        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = requireDealerForRead(company, dealerId);
        AgingSummaryResponse aging = statementService.dealerAging(
                dealer,
                companyClock.today(company),
                PORTAL_AGING_BUCKETS);
        Map<String, Object> buckets = toPortalAgingBuckets(aging);
        List<Map<String, Object>> overdueInvoices = toOverdueInvoicePayload(
                statementService.dealerOverdueInvoices(dealer, companyClock.today(company)));
        BigDecimal totalOutstanding = aging.totalOutstanding() != null ? aging.totalOutstanding() : BigDecimal.ZERO;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dealerId", dealer.getId());
        payload.put("dealerName", dealer.getName());
        payload.put("totalOutstanding", totalOutstanding);
        payload.put("agingBuckets", buckets);
        payload.put("overdueInvoices", overdueInvoices);
        return payload;
    }

    @Transactional
    public Map<String, Object> ledgerView(Long dealerId) {
        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = requireDealerForRead(company, dealerId);
        var entries = dealerLedgerService.entries(dealer);
        BigDecimal running = BigDecimal.ZERO;
        var lines = new ArrayList<Map<String, Object>>();
        for (var e : entries) {
            running = running.add(e.getDebit()).subtract(e.getCredit());
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("date", e.getEntryDate());
            line.put("reference", e.getReferenceNumber());
            line.put("memo", e.getMemo());
            line.put("debit", e.getDebit());
            line.put("credit", e.getCredit());
            line.put("runningBalance", running);
            lines.add(line);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dealerId", dealer.getId());
        payload.put("dealerName", dealer.getName());
        payload.put("currentBalance", running);
        payload.put("entries", lines);
        return payload;
    }

    private String generateRandomPassword() {
        int length = 12;
        List<Character> chars = new ArrayList<>();
        chars.add(LOWER.charAt(RANDOM.nextInt(LOWER.length())));
        chars.add(UPPER.charAt(RANDOM.nextInt(UPPER.length())));
        chars.add(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        chars.add(SPECIAL.charAt(RANDOM.nextInt(SPECIAL.length())));
        for (int i = chars.size(); i < length; i++) {
            chars.add(ALL.charAt(RANDOM.nextInt(ALL.length())));
        }
        java.util.Collections.shuffle(chars, RANDOM);
        StringBuilder sb = new StringBuilder(length);
        for (char c : chars) {
            sb.append(c);
        }
        return sb.toString();
    }

    private void scheduleCredentialEmailAfterCommit(String contactEmail, String dealerName, String rawPassword) {
        if (!StringUtils.hasText(rawPassword)) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            emailService.sendUserCredentialsEmail(contactEmail, dealerName, rawPassword);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    emailService.sendUserCredentialsEmail(contactEmail, dealerName, rawPassword);
                } catch (RuntimeException ex) {
                    log.error("Dealer credentials email failed after commit for {}", contactEmail, ex);
                }
            }
        });
    }

    private DealerResponse toResponse(Dealer dealer, String portalEmail) {
        BigDecimal balance = dealer.getId() == null ? BigDecimal.ZERO : dealerLedgerService.currentBalance(dealer.getId());
        return toResponse(dealer, portalEmail, balance);
    }

    private DealerResponse toResponse(Dealer dealer, String portalEmail, BigDecimal outstandingBalance) {
        Account receivableAccount = dealer.getReceivableAccount();
        return new DealerResponse(
                dealer.getId(),
                dealer.getPublicId(),
                dealer.getCode(),
                dealer.getName(),
                dealer.getCompanyName(),
                dealer.getEmail(),
                dealer.getPhone(),
                dealer.getAddress(),
                dealer.getCreditLimit(),
                outstandingBalance,
                receivableAccount != null ? receivableAccount.getId() : null,
                receivableAccount != null ? receivableAccount.getCode() : null,
                portalEmail,
                dealer.getGstNumber(),
                dealer.getStateCode(),
                dealer.getGstRegistrationType(),
                dealer.getPaymentTerms(),
                dealer.getRegion()
        );
    }

    private String normalizeGstNumber(String gstNumber) {
        if (!StringUtils.hasText(gstNumber)) {
            return null;
        }
        String normalized = gstNumber.trim().toUpperCase(Locale.ROOT);
        if (!GSTIN_PATTERN.matcher(normalized).matches()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("GST number must be a valid 15-character GSTIN");
        }
        return normalized;
    }

    private String normalizeStateCode(String stateCode) {
        if (!StringUtils.hasText(stateCode)) {
            return null;
        }
        String normalized = stateCode.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() != 2) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("State code must be exactly 2 characters");
        }
        return normalized;
    }

    private String normalizeRegion(String region) {
        if (!StringUtils.hasText(region)) {
            return null;
        }
        return region.trim().toUpperCase(Locale.ROOT);
    }

    private GstRegistrationType resolveRegistrationType(GstRegistrationType registrationType) {
        return registrationType == null ? GstRegistrationType.UNREGISTERED : registrationType;
    }

    private DealerPaymentTerms resolvePaymentTerms(DealerPaymentTerms paymentTerms) {
        return paymentTerms == null ? DealerPaymentTerms.NET_30 : paymentTerms;
    }

    private Dealer requireDealerForRead(Company company, Long dealerId) {
        return dealerRepository.findByCompanyAndId(company, dealerId)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Dealer not found"));
    }

    private String normalizeOptionalToken(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeCreditStatus(String creditStatus) {
        if (!StringUtils.hasText(creditStatus)) {
            return null;
        }
        String normalized = creditStatus.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("WITHIN_LIMIT")
                && !normalized.equals("NEAR_LIMIT")
                && !normalized.equals("OVER_LIMIT")) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "creditStatus must be one of WITHIN_LIMIT, NEAR_LIMIT, OVER_LIMIT");
        }
        return normalized;
    }

    private String resolveCreditStatus(Dealer dealer, BigDecimal outstandingBalance, BigDecimal pendingOrderExposure) {
        BigDecimal creditLimit = dealer.getCreditLimit() != null ? dealer.getCreditLimit() : BigDecimal.ZERO;
        BigDecimal creditUsed = safe(outstandingBalance).add(safe(pendingOrderExposure));
        if (creditLimit.compareTo(BigDecimal.ZERO) <= 0) {
            return creditUsed.compareTo(BigDecimal.ZERO) > 0 ? "OVER_LIMIT" : "WITHIN_LIMIT";
        }
        BigDecimal ratio = creditUsed.divide(creditLimit, 4, RoundingMode.HALF_UP);
        if (ratio.compareTo(BigDecimal.ONE) >= 0) {
            return "OVER_LIMIT";
        }
        if (ratio.compareTo(new BigDecimal("0.80")) >= 0) {
            return "NEAR_LIMIT";
        }
        return "WITHIN_LIMIT";
    }

    private BigDecimal resolvePendingOrderExposure(Dealer dealer) {
        if (dealer == null || dealer.getId() == null || dealer.getCompany() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal exposure = salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(
                dealer.getCompany(),
                dealer,
                SalesOrderCreditExposurePolicy.pendingCreditExposureStatuses(),
                null
        );
        return exposure != null ? exposure : BigDecimal.ZERO;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Map<String, Object> toPortalAgingBuckets(AgingSummaryResponse aging) {
        Map<String, Object> buckets = new LinkedHashMap<>();
        buckets.put("current", BigDecimal.ZERO);
        buckets.put("1-30 days", BigDecimal.ZERO);
        buckets.put("31-60 days", BigDecimal.ZERO);
        buckets.put("61-90 days", BigDecimal.ZERO);
        buckets.put("90+ days", BigDecimal.ZERO);
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
            }
        }
        return buckets;
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

    private List<Map<String, Object>> toOverdueInvoicePayload(List<OverdueInvoiceDto> overdueInvoices) {
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
