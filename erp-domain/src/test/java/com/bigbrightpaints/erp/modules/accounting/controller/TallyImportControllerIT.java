package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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

class TallyImportControllerIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "TALLY-IT";
    private static final String PASSWORD = "changeme";
    private static final String ADMIN_EMAIL = "tally-it-admin@bbp.com";
    private static final String SUPER_ADMIN_EMAIL = "tally-it-superadmin@bbp.com";
    private static final String PLATFORM_ONLY_MESSAGE =
            "Super Admin is limited to platform control-plane operations and cannot execute tenant business workflows";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AccountRepository accountRepository;

    @BeforeEach
    void setUpUsersAndDefaults() {
        dataSeeder.ensureUser(ADMIN_EMAIL, PASSWORD, "Tally IT Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser(SUPER_ADMIN_EMAIL, PASSWORD, "Tally IT Super Admin", COMPANY_CODE,
                List.of("ROLE_SUPER_ADMIN"));

        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Tally IT Co");
        ensureAccount(company, "CUSTOMER-A", "Customer A", AccountType.ASSET);
        ensureAccount(company, "SUPPLIER-B", "Supplier B", AccountType.LIABILITY);
        ensureAccount(company, "BANK-001", "Bank", AccountType.ASSET);
        ensureAccount(company, "CAP-001", "Capital", AccountType.EQUITY);
    }

    @Test
    void admin_canImportTallyData() {
        ResponseEntity<Map> response = importTally(
                authHeaders(ADMIN_EMAIL),
                sampleTallyXml(),
                "tally-admin.xml"
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("success")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void superAdmin_cannotExecuteTenantTallyImportWorkflow() {
        ResponseEntity<Map> response = importTally(
                authHeaders(SUPER_ADMIN_EMAIL),
                sampleTallyXml(),
                "tally-superadmin.xml"
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message")).isEqualTo("Access denied");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("reason")).isEqualTo("SUPER_ADMIN_PLATFORM_ONLY");
        assertThat(data.get("reasonDetail")).isEqualTo(PLATFORM_ONLY_MESSAGE);
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

    private HttpHeaders authHeaders(String email) {
        ResponseEntity<Map> login = rest.postForEntity(
                "/api/v1/auth/login",
                Map.of(
                        "email", email,
                        "password", PASSWORD,
                        "companyCode", COMPANY_CODE
                ),
                Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth((String) login.getBody().get("accessToken"));
        headers.set("X-Company-Id", COMPANY_CODE);
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
