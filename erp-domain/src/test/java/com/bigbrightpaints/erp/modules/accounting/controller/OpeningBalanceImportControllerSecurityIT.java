package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.OpeningBalanceImport;
import com.bigbrightpaints.erp.modules.accounting.domain.OpeningBalanceImportRepository;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OpeningBalanceImportControllerSecurityIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "OBAL-SEC-A1";
    private static final String OTHER_COMPANY_CODE = "OBAL-SEC-B1";
    private static final String PASSWORD = "changeme";
    private static final String ADMIN_EMAIL = "opening-bal-admin@bbp.com";
    private static final String ACCOUNTING_EMAIL = "opening-bal-accounting@bbp.com";
    private static final String OTHER_ADMIN_EMAIL = "opening-bal-admin-b@bbp.com";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private OpeningBalanceImportRepository openingBalanceImportRepository;
    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @BeforeEach
    void setUpUsersAndDefaults() {
        dataSeeder.ensureUser(ADMIN_EMAIL, PASSWORD, "Opening Balance Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser(ACCOUNTING_EMAIL, PASSWORD, "Opening Balance Accounting", COMPANY_CODE,
                List.of("ROLE_ACCOUNTING"));
        dataSeeder.ensureCompany(OTHER_COMPANY_CODE, "Opening Balance Other Co");
        dataSeeder.ensureUser(OTHER_ADMIN_EMAIL, PASSWORD, "Opening Balance Admin B", OTHER_COMPANY_CODE,
                List.of("ROLE_ADMIN"));

        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Opening Balance Co");
        ensureAccount(company, "BANK-001", "Main Bank", AccountType.ASSET);
        ensureAccount(company, "DEBTOR-001", "Debtors Control", AccountType.ASSET);
        ensureAccount(company, "CAP-001", "Capital", AccountType.EQUITY);

        Company otherCompany = dataSeeder.ensureCompany(OTHER_COMPANY_CODE, "Opening Balance Other Co");
        ensureAccount(otherCompany, "BANK-001", "Main Bank", AccountType.ASSET);
        ensureAccount(otherCompany, "CAP-001", "Capital", AccountType.EQUITY);
    }

    @Test
    void openingBalanceImport_allowsAdminOnly() {
        ResponseEntity<Map> adminResponse = importOpeningBalances(
                authHeaders(ADMIN_EMAIL, PASSWORD, COMPANY_CODE),
                "BANK-001,Main Bank,ASSET,500.00,0,Opening bank\n"
                        + "CAP-001,Capital,EQUITY,0,500.00,Opening capital\n",
                "opening-balances-admin.csv");
        assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> accountingResponse = importOpeningBalances(
                authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE),
                "BANK-001,Main Bank,ASSET,100.00,0,Opening bank\n"
                        + "CAP-001,Capital,EQUITY,0,100.00,Opening capital\n",
                "opening-balances-accounting.csv");
        assertThat(accountingResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void openingBalanceImport_sameFileReplaysByHashAndCreatesSingleImportRecord() {
        String csvRows = "BANK-001,Main Bank,ASSET,750.00,0,Opening bank\n"
                + "DEBTOR-001,Debtors Control,ASSET,250.00,0,Opening debtors\n"
                + "CAP-001,Capital,EQUITY,0,1000.00,Opening capital\n";
        String csvPayload = "account_code,account_name,account_type,debit_amount,credit_amount,narration\n" + csvRows;
        String expectedHash = IdempotencyUtils.sha256Hex(csvPayload.getBytes(StandardCharsets.UTF_8));

        HttpHeaders headers = authHeaders(ADMIN_EMAIL, PASSWORD, COMPANY_CODE);
        ResponseEntity<Map> first = importOpeningBalances(headers, csvRows, "opening-balances-first.csv");
        ResponseEntity<Map> replay = importOpeningBalances(headers, csvRows, "opening-balances-replay.csv");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);

        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        OpeningBalanceImport importRecord = openingBalanceImportRepository
                .findByCompanyAndIdempotencyKey(company, expectedHash)
                .orElseThrow();
        assertThat(importRecord.getRowsProcessed()).isEqualTo(3);
        assertThat(importRecord.getJournalEntryId()).isNotNull();

        Optional<JournalEntry> journal = journalEntryRepository.findByCompanyAndId(company, importRecord.getJournalEntryId());
        assertThat(journal).isPresent();
        assertThat(journal.orElseThrow().getReferenceNumber()).startsWith("OPEN-BAL-OBALSECA1-");
    }

    private ResponseEntity<Map> importOpeningBalances(HttpHeaders headers, String csvRows, String fileName) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType("text/csv"));
        body.add("file", new HttpEntity<>(csvResource(fileName, csvRows), fileHeaders));

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.putAll(headers);
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        return rest.exchange(
                "/api/v1/accounting/opening-balances",
                HttpMethod.POST,
                new HttpEntity<>(body, requestHeaders),
                Map.class);
    }

    private HttpHeaders authHeaders(String email, String password, String companyCode) {
        Map<String, Object> loginPayload = Map.of(
                "email", email,
                "password", password,
                "companyCode", companyCode
        );
        ResponseEntity<Map> loginResponse = rest.postForEntity("/api/v1/auth/login", loginPayload, Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        String token = (String) loginResponse.getBody().get("accessToken");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Company-Code", companyCode);
        return headers;
    }

    private ByteArrayResource csvResource(String fileName, String csvRows) {
        String payload = "account_code,account_name,account_type,debit_amount,credit_amount,narration\n" + csvRows;
        return new ByteArrayResource(payload.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    private void ensureAccount(Company company, String code, String name, AccountType type) {
        accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setCompany(company);
                    account.setCode(code);
                    account.setName(name);
                    account.setType(type);
                    return accountRepository.save(account);
                });
    }

}
