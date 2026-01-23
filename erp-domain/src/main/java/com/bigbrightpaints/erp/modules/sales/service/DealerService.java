package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.dto.CreateDealerRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DealerLookupResponse;
import com.bigbrightpaints.erp.modules.sales.dto.DealerResponse;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class DealerService {

    private static final int DEALER_SEARCH_LIMIT = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DealerRepository dealerRepository;
    private final CompanyContextService companyContextService;
    private final UserAccountRepository userAccountRepository;
    private final RoleService roleService;
    private final PasswordEncoder passwordEncoder;
    private final AccountRepository accountRepository;
    private final DealerLedgerService dealerLedgerService;

    public DealerService(DealerRepository dealerRepository,
                         CompanyContextService companyContextService,
                         UserAccountRepository userAccountRepository,
                         RoleService roleService,
                         PasswordEncoder passwordEncoder,
                         AccountRepository accountRepository,
                         DealerLedgerService dealerLedgerService) {
        this.dealerRepository = dealerRepository;
        this.companyContextService = companyContextService;
        this.userAccountRepository = userAccountRepository;
        this.roleService = roleService;
        this.passwordEncoder = passwordEncoder;
        this.accountRepository = accountRepository;
        this.dealerLedgerService = dealerLedgerService;
    }

    @Transactional
    public DealerResponse createDealer(CreateDealerRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        String dealerCode = generateDealerCode(request.name(), company);

        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName(request.name().trim());
        dealer.setCode(dealerCode);
        dealer.setEmail(request.contactEmail().trim());
        dealer.setPhone(request.contactPhone().trim());
        dealer.setAddress(request.address());
        dealer.setCreditLimit(request.creditLimit() != null ? request.creditLimit() : dealer.getCreditLimit());

        dealer = dealerRepository.save(dealer);

        String portalEmail = resolvePortalEmail(request.contactEmail().trim(), dealerCode);
        String rawPassword = generateRandomPassword();

        UserAccount portalUser = new UserAccount(portalEmail, passwordEncoder.encode(rawPassword), dealer.getName());
        portalUser.getRoles().add(roleService.ensureRoleExists("ROLE_DEALER"));
        portalUser.getCompanies().add(company);
        portalUser = userAccountRepository.save(portalUser);

        Account receivableAccount = createReceivableAccount(company, dealer);

        dealer.setPortalUser(portalUser);
        dealer.setReceivableAccount(receivableAccount);
        dealer = dealerRepository.save(dealer);

        return toResponse(dealer, portalEmail, rawPassword);
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
                        null,
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
                null, 
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
        var lines = new java.util.ArrayList<Map<String, Object>>();
        for (var e : entries) {
            running = running.add(e.getDebit()).subtract(e.getCredit());
            lines.add(Map.of(
                    "date", e.getEntryDate(),
                    "reference", e.getReferenceNumber(),
                    "memo", e.getMemo(),
                    "debit", e.getDebit(),
                    "credit", e.getCredit(),
                    "runningBalance", running
            ));
        }
        return Map.of(
                "dealerId", dealer.getId(),
                "dealerName", dealer.getName(),
                "currentBalance", running,
                "entries", lines
        );
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

    private String resolvePortalEmail(String requestedEmail, String dealerCode) {
        if (!userAccountRepository.findByEmailIgnoreCase(requestedEmail).isPresent()) {
            return requestedEmail;
        }
        return dealerCode.toLowerCase(Locale.ROOT) + "@dealers.local";
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
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12) + RANDOM.nextInt(10);
    }

    private DealerResponse toResponse(Dealer dealer, String portalEmail, String generatedPassword) {
        BigDecimal balance = dealer.getId() == null ? BigDecimal.ZERO : dealerLedgerService.currentBalance(dealer.getId());
        return toResponse(dealer, portalEmail, generatedPassword, balance);
    }

    private DealerResponse toResponse(Dealer dealer, String portalEmail, String generatedPassword, BigDecimal outstandingBalance) {
        Account receivableAccount = dealer.getReceivableAccount();
        return new DealerResponse(
                dealer.getId(),
                dealer.getPublicId(),
                dealer.getCode(),
                dealer.getName(),
                dealer.getEmail(),
                dealer.getPhone(),
                dealer.getAddress(),
                dealer.getCreditLimit(),
                outstandingBalance,
                receivableAccount != null ? receivableAccount.getId() : null,
                receivableAccount != null ? receivableAccount.getCode() : null,
                portalEmail,
                generatedPassword
        );
    }
}
