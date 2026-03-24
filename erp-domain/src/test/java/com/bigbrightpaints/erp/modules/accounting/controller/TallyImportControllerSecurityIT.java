package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.TallyImport;
import com.bigbrightpaints.erp.modules.accounting.domain.TallyImportRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

class TallyImportControllerSecurityIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "TALLY-SEC-A";
    private static final String PASSWORD = "changeme";
    private static final String ADMIN_EMAIL = "tally-admin@bbp.com";
    private static final String ACCOUNTING_EMAIL = "tally-accounting@bbp.com";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TallyImportRepository tallyImportRepository;

    @BeforeEach
    void setUpUsersAndDefaults() {
        dataSeeder.ensureUser(ADMIN_EMAIL, PASSWORD, "Tally Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser(ACCOUNTING_EMAIL, PASSWORD, "Tally Accounting", COMPANY_CODE,
                List.of("ROLE_ACCOUNTING"));

        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Tally Import Co");
        ensureAccount(company, "CUSTOMER-A", "Customer A", AccountType.ASSET);
        ensureAccount(company, "SUPPLIER-B", "Supplier B", AccountType.LIABILITY);
        ensureAccount(company, "BANK-001", "Bank", AccountType.ASSET);
        ensureAccount(company, "CAP-001", "Capital", AccountType.EQUITY);
    }

    @Test
    void tallyImport_allowsAdminOnly() {
        ResponseEntity<Map> adminResponse = importTally(
                authHeaders(ADMIN_EMAIL, PASSWORD, COMPANY_CODE),
                sampleTallyXml(),
                "tally-admin.xml"
        );
        assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> accountingResponse = importTally(
                authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE),
                sampleTallyXml(),
                "tally-accounting.xml"
        );
        assertThat(accountingResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void tallyImport_sameFileReplaysByHashAndCreatesSingleImportRecord() {
        String xml = sampleTallyXml();

        HttpHeaders headers = authHeaders(ADMIN_EMAIL, PASSWORD, COMPANY_CODE);
        ResponseEntity<Map> first = importTally(headers, xml, "tally-first.xml");
        ResponseEntity<Map> replay = importTally(headers, xml, "tally-replay.xml");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);

        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        String expectedHash = com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils
                .sha256Hex(xml.getBytes(StandardCharsets.UTF_8));
        TallyImport importRecord = tallyImportRepository
                .findByCompanyAndIdempotencyKey(company, expectedHash)
                .orElseThrow();

        assertThat(importRecord.getLedgersProcessed()).isEqualTo(2);
        assertThat(importRecord.getMappedLedgers()).isEqualTo(2);
        assertThat(importRecord.getOpeningVoucherEntriesProcessed()).isEqualTo(2);
    }

    private ResponseEntity<Map> importTally(HttpHeaders headers, String xml, String fileName) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType("application/xml"));
        body.add("file", new HttpEntity<>(xmlResource(fileName, xml), fileHeaders));

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.putAll(headers);
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        return rest.exchange(
                "/api/v1/migration/tally-import",
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

    private ByteArrayResource xmlResource(String fileName, String xml) {
        return new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8)) {
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

    private String sampleTallyXml() {
        return """
                <ENVELOPE>
                  <BODY>
                    <DATA>
                      <TALLYMESSAGE>
                        <LEDGER NAME=\"Customer A\">
                          <PARENT>Sundry Debtors</PARENT>
                        </LEDGER>
                      </TALLYMESSAGE>
                      <TALLYMESSAGE>
                        <LEDGER NAME=\"Supplier B\">
                          <PARENT>Sundry Creditors</PARENT>
                        </LEDGER>
                      </TALLYMESSAGE>
                      <TALLYMESSAGE>
                        <VOUCHER VCHTYPE=\"Opening Balance\" VOUCHERTYPENAME=\"Opening Balance\">
                          <ALLLEDGERENTRIES.LIST>
                            <LEDGERNAME>Customer A</LEDGERNAME>
                            <AMOUNT>1200.00</AMOUNT>
                          </ALLLEDGERENTRIES.LIST>
                          <ALLLEDGERENTRIES.LIST>
                            <LEDGERNAME>Supplier B</LEDGERNAME>
                            <AMOUNT>-1200.00</AMOUNT>
                          </ALLLEDGERENTRIES.LIST>
                        </VOUCHER>
                      </TALLYMESSAGE>
                    </DATA>
                  </BODY>
                </ENVELOPE>
                """;
    }
}
