package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.dto.CreateDealerRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DealerLookupResponse;
import com.bigbrightpaints.erp.modules.sales.dto.DealerResponse;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    private final DealerRepository dealerRepository;
    private final CompanyContextService companyContextService;
    private final UserAccountRepository userAccountRepository;
    private final RoleService roleService;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final AccountRepository accountRepository;
    private final DealerLedgerService dealerLedgerService;

    public DealerService(DealerRepository dealerRepository,
                         CompanyContextService companyContextService,
                         UserAccountRepository userAccountRepository,
                         RoleService roleService,
                         PasswordEncoder passwordEncoder,
                         EmailService emailService,
                         AccountRepository accountRepository,
                         DealerLedgerService dealerLedgerService) {
        this.dealerRepository = dealerRepository;
        this.companyContextService = companyContextService;
        this.userAccountRepository = userAccountRepository;
        this.roleService = roleService;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.accountRepository = accountRepository;
        this.dealerLedgerService = dealerLedgerService;
    }

    @Transactional
    public DealerResponse createDealer(CreateDealerRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        String dealerCode = generateDealerCode(request.name(), company);

        String contactEmail = request.contactEmail().trim();
        dealerRepository.findByCompanyAndPortalUserEmail(company, contactEmail)
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Dealer already exists for this portal user");
                });

        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName(request.name().trim());
        dealer.setCompanyName(request.companyName().trim());
        dealer.setCode(dealerCode);
        dealer.setEmail(contactEmail);
        dealer.setPhone(request.contactPhone().trim());
        dealer.setAddress(request.address());
        dealer.setCreditLimit(request.creditLimit() != null ? request.creditLimit() : dealer.getCreditLimit());

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

        Account receivableAccount = createReceivableAccount(company, dealer);

        dealer.setPortalUser(portalUser);
        dealer.setReceivableAccount(receivableAccount);
        dealer = dealerRepository.save(dealer);

        scheduleCredentialEmailAfterCommit(contactEmail, dealer.getName(), rawPassword);
        return toResponse(dealer, portalUser.getEmail());
    }

    @Transactional
    public List<DealerResponse> listDealers() {
        Company company = companyContextService.requireCurrentCompany();
        List<Dealer> dealers = dealerRepository.findByCompanyOrderByNameAsc(company);
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
    public List<DealerLookupResponse> search(String query) {
        Company company = companyContextService.requireCurrentCompany();
        String term = StringUtils.hasText(query) ? query.trim() : "";
        List<Dealer> matches = dealerRepository.search(company, term, PageRequest.of(0, DEALER_SEARCH_LIMIT));
        List<Long> dealerIds = matches.stream().map(Dealer::getId).toList();
        var balances = dealerLedgerService.currentBalances(dealerIds);
        return matches.stream()
                .map(dealer -> {
                    Account receivableAccount = dealer.getReceivableAccount();
                    return new DealerLookupResponse(
                            dealer.getId(),
                            dealer.getPublicId(),
                            dealer.getName(),
                            dealer.getCode(),
                            balances.getOrDefault(dealer.getId(), BigDecimal.ZERO),
                            dealer.getCreditLimit(),
                            receivableAccount != null ? receivableAccount.getId() : null,
                            receivableAccount != null ? receivableAccount.getCode() : null
                    );
                })
                .toList();
    }

    @Transactional
    public DealerResponse updateDealer(Long dealerId, CreateDealerRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = dealerRepository.findByCompanyAndId(company, dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Dealer not found"));
        
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
        if (request.creditLimit() != null) {
            dealer.setCreditLimit(request.creditLimit());
        }
        
        dealer = dealerRepository.save(dealer);
        BigDecimal balance = dealerLedgerService.currentBalance(dealer.getId());
        return toResponse(dealer, 
                dealer.getPortalUser() != null ? dealer.getPortalUser().getEmail() : null,
                balance);
    }

    /**
     * Returns a human-readable ledger view for a dealer including running balance.
     */
    @Transactional
    public Map<String, Object> ledgerView(Long dealerId) {
        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = dealerRepository.findByCompanyAndId(company, dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Dealer not found"));
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

    private String generateDealerCode(String input, Company company) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9]", "")
                .toUpperCase(Locale.ROOT);
        String base = normalized.isEmpty() ? "DEALER" : normalized;
        String code = base;
        int attempt = 1;
        while (dealerRepository.findByCompanyAndCodeIgnoreCase(company, code).isPresent()) {
            code = base + attempt++;
        }
        return code;
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
                portalEmail
        );
    }
}
