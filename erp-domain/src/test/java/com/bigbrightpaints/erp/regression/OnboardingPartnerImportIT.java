package com.bigbrightpaints.erp.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
class OnboardingPartnerImportIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "changeme";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private SupplierRepository supplierRepository;

  private String companyCode;
  private Company company;
  private HttpHeaders adminHeaders;

  @BeforeEach
  void setUp() {
    companyCode = "ONBOARD-IMP-" + shortId();
    company = dataSeeder.ensureCompany(companyCode, "Onboarding Imports " + shortId());
    String adminEmail = "onboarding-admin-" + shortId() + "@bbp.com";
    dataSeeder.ensureUser(
        adminEmail, PASSWORD, "Onboarding Admin", companyCode, List.of("ROLE_ADMIN"));
    adminHeaders = authHeaders(adminEmail, PASSWORD, companyCode);
  }

  @Test
  void guidedOnboardingPartnerImportsReturnCountsAndRowLevelErrors() {
    ResponseEntity<Map> accountsResponse =
        rest.exchange(
            "/api/v1/accounting/accounts",
            HttpMethod.GET,
            new HttpEntity<>(adminHeaders),
            Map.class);

    assertThat(accountsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> accounts = (List<Map<String, Object>>) responseData(accountsResponse);
    assertThat(accounts).isNotEmpty();

    String dealerEmail = "dealer-" + shortId() + "@example.com";
    ResponseEntity<Map> dealerImportResponse =
        postCsvImport(
            "/api/v1/dealers/import",
            "dealers.csv",
            "name,email,creditLimit,region,paymentTerms,gstNumber,stateCode\n"
                + "Dealer One,"
                + dealerEmail
                + ",10000,NORTH,NET_30,27ABCDE1234F1Z5,KA\n"
                + "Dealer Broken,broken-at-example.com,2000,SOUTH,NET_30,INVALID,123\n");

    assertThat(dealerImportResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> dealerPayload = (Map<String, Object>) responseData(dealerImportResponse);
    assertThat(((Number) dealerPayload.get("successCount")).intValue()).isEqualTo(1);
    assertThat(((Number) dealerPayload.get("failureCount")).intValue()).isEqualTo(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> dealerErrors =
        (List<Map<String, Object>>) dealerPayload.get("errors");
    assertThat(dealerErrors).hasSize(1);
    assertThat(((Number) dealerErrors.getFirst().get("rowNumber")).longValue()).isEqualTo(2L);
    assertThat(String.valueOf(dealerErrors.getFirst().get("message")))
        .contains("contactEmail: Provide a valid contact email")
        .contains("gstNumber: GST number must be a valid 15-character GSTIN")
        .contains("stateCode: State code must be exactly 2 characters");
    assertThat(dealerRepository.findByCompanyAndEmailIgnoreCase(company, dealerEmail)).isPresent();
    assertThat(dealerRepository.findByCompanyAndEmailIgnoreCase(company, "broken-at-example.com"))
        .isNotPresent();

    String supplierCode = "SUP-" + shortId();
    ResponseEntity<Map> supplierImportResponse =
        postCsvImport(
            "/api/v1/suppliers/import",
            "suppliers.csv",
            "name,email,creditLimit,paymentTerms,code,gstNumber,stateCode\n"
                + "Supplier One,supplier-"
                + shortId()
                + "@example.com,5000,NET_30,"
                + supplierCode
                + ",27ABCDE1234F1Z5,KA\n"
                + "Supplier Broken,invalid-email,3000,NET_30,SUP-BROKEN,INVALID,123\n");

    assertThat(supplierImportResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> supplierPayload =
        (Map<String, Object>) responseData(supplierImportResponse);
    assertThat(((Number) supplierPayload.get("successCount")).intValue()).isEqualTo(1);
    assertThat(((Number) supplierPayload.get("failureCount")).intValue()).isEqualTo(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> supplierErrors =
        (List<Map<String, Object>>) supplierPayload.get("errors");
    assertThat(supplierErrors).hasSize(1);
    assertThat(((Number) supplierErrors.getFirst().get("rowNumber")).longValue()).isEqualTo(2L);
    assertThat(String.valueOf(supplierErrors.getFirst().get("message")))
        .contains("contactEmail: must be a well-formed email address")
        .contains("gstNumber: GST number must be a valid 15-character GSTIN")
        .contains("stateCode: State code must be exactly 2 characters");
    assertThat(supplierRepository.findByCompanyAndCodeIgnoreCase(company, supplierCode))
        .isPresent();
    assertThat(supplierRepository.findByCompanyAndCodeIgnoreCase(company, "SUP-BROKEN"))
        .isNotPresent();

    String catalogProductName = "OnboardPaint" + shortId();
    ResponseEntity<Map> catalogImportResponse =
        postCsvImport(
            "/api/v1/catalog/import",
            "catalog.csv",
            "brand,product_name,category,default_colour,size,unit_of_measure,base_price,gst_rate,min_discount_percent,min_selling_price\n"
                + "OnboardBrand,"
                + catalogProductName
                + ",EMULSION,WHITE,1L,L,100.00,18,5,95.00\n");

    assertThat(catalogImportResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> catalogImportPayload =
        (Map<String, Object>) responseData(catalogImportResponse);
    assertThat(((Number) catalogImportPayload.get("rowsProcessed")).intValue()).isEqualTo(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> catalogImportErrors =
        (List<Map<String, Object>>) catalogImportPayload.get("errors");
    assertThat(catalogImportErrors).isEmpty();

    ResponseEntity<Map> catalogItemsResponse =
        rest.exchange(
            "/api/v1/catalog/items?q=" + catalogProductName + "&includeReadiness=true",
            HttpMethod.GET,
            new HttpEntity<>(adminHeaders),
            Map.class);
    assertThat(catalogItemsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> catalogItemsPage = (Map<String, Object>) responseData(catalogItemsResponse);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> catalogItems =
        (List<Map<String, Object>>) catalogItemsPage.get("content");
    assertThat(catalogItems).isNotEmpty();
    Map<String, Object> importedItem = catalogItems.getFirst();
    @SuppressWarnings("unchecked")
    Map<String, Object> metadata = (Map<String, Object>) importedItem.get("metadata");
    assertThat(metadata.get("fgValuationAccountId")).isInstanceOf(Number.class);
    assertThat(metadata.get("fgCogsAccountId")).isInstanceOf(Number.class);
    assertThat(metadata.get("fgRevenueAccountId")).isInstanceOf(Number.class);
  }

  private ResponseEntity<Map> postCsvImport(String path, String fileName, String csvPayload) {
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.parseMediaType("text/csv"));
    body.add("file", new HttpEntity<>(csvResource(fileName, csvPayload), fileHeaders));

    HttpHeaders requestHeaders = new HttpHeaders();
    requestHeaders.putAll(adminHeaders);
    requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

    return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, requestHeaders), Map.class);
  }

  private ByteArrayResource csvResource(String fileName, String csvPayload) {
    return new ByteArrayResource(csvPayload.getBytes(StandardCharsets.UTF_8)) {
      @Override
      public String getFilename() {
        return fileName;
      }
    };
  }

  private Object responseData(ResponseEntity<Map> response) {
    assertThat(response.getBody()).isNotNull();
    return response.getBody().get("data");
  }

  private HttpHeaders authHeaders(String email, String password, String tenantCode) {
    Map<String, Object> loginPayload =
        Map.of("email", email, "password", password, "companyCode", tenantCode);
    ResponseEntity<Map> loginResponse =
        rest.postForEntity("/api/v1/auth/login", loginPayload, Map.class);
    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    String token = String.valueOf(loginResponse.getBody().get("accessToken"));

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.set("X-Company-Code", tenantCode);
    return headers;
  }

  private String shortId() {
    String value = Long.toHexString(System.nanoTime()).toUpperCase();
    return value.substring(Math.max(0, value.length() - 6));
  }
}
