package com.bigbrightpaints.erp.e2e.sales;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("E2E: Sales Return Credit Note Flow")
public class SalesReturnCreditNoteE2EIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "SRN-E2E";
    private static final String ADMIN_EMAIL = "salesreturn@e2e.com";
    private static final String ADMIN_PASSWORD = "return123";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private DealerRepository dealerRepository;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private SalesOrderRepository salesOrderRepository;

    private HttpHeaders headers;
    private Company company;
    private Account ar;
    private Account revenue;
    private Account discount;
    private Account gstInput;
    private Account gstOutput;
    private Account inventory;
    private Account cogs;
    private Dealer dealer;
    private FinishedGood finishedGood;
    private SalesOrder salesOrder;
    private Invoice invoice;
    private InvoiceLine invoiceLine;

    @BeforeEach
    void setup() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Sales Return Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SALES"));
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        headers = authHeaders();

        ar = ensureAccount("AR-SRN", "Accounts Receivable", AccountType.ASSET);
        revenue = ensureAccount("REV-SRN", "Revenue", AccountType.REVENUE);
        discount = ensureAccount("DISC-SRN", "Discounts", AccountType.EXPENSE);
        gstInput = ensureAccount("GST-IN-SRN", "GST Input", AccountType.ASSET);
        gstOutput = ensureAccount("GST-OUT-SRN", "GST Output", AccountType.LIABILITY);
        inventory = ensureAccount("INV-SRN", "Inventory", AccountType.ASSET);
        cogs = ensureAccount("COGS-SRN", "COGS", AccountType.COGS);

        company.setGstInputTaxAccountId(gstInput.getId());
        company.setGstOutputTaxAccountId(gstOutput.getId());
        companyRepository.save(company);

        dealer = ensureDealer();
        finishedGood = ensureFinishedGood();
        salesOrder = ensureSalesOrder();
        invoice = ensureInvoice();
        invoiceLine = invoice.getLines().get(0);
    }

    @Test
    @DisplayName("Invoice -> sales return -> credit note journal + inventory reconciliation")
    void salesReturn_postsCreditNoteAndRestocksInventory() {
        seedDispatchMovement();
        BigDecimal startingStock = finishedGoodRepository.findById(finishedGood.getId())
                .orElseThrow()
                .getCurrentStock();
        Map<String, Object> payload = Map.of(
                "invoiceId", invoice.getId(),
                "reason", "Damaged goods",
                "lines", List.of(Map.of(
                        "invoiceLineId", invoiceLine.getId(),
                        "quantity", new BigDecimal("1.00")
                ))
        );

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/accounting/sales/returns",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        Long entryId = ((Number) data.get("id")).longValue();

        JournalEntry entry = journalEntryRepository.findById(entryId).orElseThrow();
        String returnReference = "CRN-" + invoice.getInvoiceNumber();
        assertThat(entry.getReferenceNumber()).isEqualTo(returnReference);

        BigDecimal debitTotal = entry.getLines().stream()
                .map(line -> Optional.ofNullable(line.getDebit()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditTotal = entry.getLines().stream()
                .map(line -> Optional.ofNullable(line.getCredit()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debitTotal).isEqualByComparingTo(creditTotal);

        BigDecimal arCredit = entry.getLines().stream()
                .filter(line -> line.getAccount().getId().equals(ar.getId()))
                .map(line -> Optional.ofNullable(line.getCredit()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(arCredit).isEqualByComparingTo("104.50");

        BigDecimal revenueDebit = sumAccount(entry, revenue.getId(), true);
        BigDecimal discountCredit = sumAccount(entry, discount.getId(), false);
        BigDecimal taxDebit = sumAccount(entry, gstOutput.getId(), true);
        assertThat(revenueDebit).isEqualByComparingTo("100.00");
        assertThat(discountCredit).isEqualByComparingTo("5.00");
        assertThat(taxDebit).isEqualByComparingTo("9.50");

        FinishedGood refreshed = finishedGoodRepository.findById(finishedGood.getId()).orElseThrow();
        assertThat(refreshed.getCurrentStock()).isEqualByComparingTo(startingStock.add(new BigDecimal("1.00")));

        List<InventoryMovement> returnMovements = inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc("SALES_RETURN", invoice.getInvoiceNumber());
        assertThat(returnMovements).hasSize(1);
        InventoryMovement returnMovement = returnMovements.get(0);
        assertThat(returnMovement.getMovementType()).isEqualTo("RETURN");
        assertThat(returnMovement.getQuantity()).isEqualByComparingTo("1.00");
        assertThat(returnMovement.getUnitCost()).isEqualByComparingTo("50.00");

        String cogsReference = "CRN-" + invoice.getInvoiceNumber() + "-COGS-0";
        JournalEntry cogsEntry = journalEntryRepository
                .findByCompanyAndReferenceNumber(company, cogsReference)
                .flatMap(existing -> journalEntryRepository.findById(existing.getId()))
                .orElseThrow();
        BigDecimal cogsDebits = cogsEntry.getLines().stream()
                .map(line -> Optional.ofNullable(line.getDebit()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cogsCredits = cogsEntry.getLines().stream()
                .map(line -> Optional.ofNullable(line.getCredit()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(cogsDebits).isEqualByComparingTo(cogsCredits);
    }

    @Test
    @DisplayName("Sales return with mixed discounts posts correct revenue/tax/discount lines")
    void salesReturn_mixedDiscounts_postsCorrectLines() {
        Invoice mixedInvoice = ensureInvoiceWithMixedDiscounts();
        InvoiceLine discountedLine = mixedInvoice.getLines().get(0);
        InvoiceLine fullPriceLine = mixedInvoice.getLines().get(1);

        seedDispatchMovement(new BigDecimal("2.00"), new BigDecimal("50.00"));
        BigDecimal startingStock = finishedGoodRepository.findById(finishedGood.getId())
                .orElseThrow()
                .getCurrentStock();

        Map<String, Object> payload = Map.of(
                "invoiceId", mixedInvoice.getId(),
                "reason", "Mixed discount return",
                "lines", List.of(
                        Map.of(
                                "invoiceLineId", discountedLine.getId(),
                                "quantity", new BigDecimal("1.00")
                        ),
                        Map.of(
                                "invoiceLineId", fullPriceLine.getId(),
                                "quantity", new BigDecimal("1.00")
                        )
                )
        );

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/accounting/sales/returns",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        Long entryId = ((Number) data.get("id")).longValue();

        JournalEntry entry = journalEntryRepository.findById(entryId).orElseThrow();
        assertThat(sumAccount(entry, ar.getId(), false)).isEqualByComparingTo("159.50");
        assertThat(sumAccount(entry, revenue.getId(), true)).isEqualByComparingTo("150.00");
        assertThat(sumAccount(entry, discount.getId(), false)).isEqualByComparingTo("5.00");
        assertThat(sumAccount(entry, gstOutput.getId(), true)).isEqualByComparingTo("14.50");

        FinishedGood refreshed = finishedGoodRepository.findById(finishedGood.getId()).orElseThrow();
        assertThat(refreshed.getCurrentStock()).isEqualByComparingTo(startingStock.add(new BigDecimal("2.00")));

        List<InventoryMovement> returnMovements = inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc("SALES_RETURN", mixedInvoice.getInvoiceNumber());
        assertThat(returnMovements).hasSize(2);
        BigDecimal totalReturned = returnMovements.stream()
                .map(mv -> Optional.ofNullable(mv.getQuantity()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalReturned).isEqualByComparingTo("2.00");

        String cogsReference = "CRN-" + mixedInvoice.getInvoiceNumber() + "-COGS-0";
        JournalEntry cogsEntry = journalEntryRepository
                .findByCompanyAndReferenceNumber(company, cogsReference)
                .flatMap(existing -> journalEntryRepository.findById(existing.getId()))
                .orElseThrow();
        assertThat(sumAccount(cogsEntry, inventory.getId(), true)).isEqualByComparingTo("100.00");
        assertThat(sumAccount(cogsEntry, cogs.getId(), false)).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Sales return without dispatch cost layers is rejected")
    void salesReturn_withoutDispatchCostLayers_rejected() {
        BigDecimal startingStock = finishedGoodRepository.findById(finishedGood.getId())
                .orElseThrow()
                .getCurrentStock();
        Map<String, Object> payload = Map.of(
                "invoiceId", invoice.getId(),
                "reason", "Missing dispatch cost layer",
                "lines", List.of(Map.of(
                        "invoiceLineId", invoiceLine.getId(),
                        "quantity", new BigDecimal("1.00")
                ))
        );

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/accounting/sales/returns",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        FinishedGood refreshed = finishedGoodRepository.findById(finishedGood.getId()).orElseThrow();
        assertThat(refreshed.getCurrentStock()).isEqualByComparingTo(startingStock);

        List<InventoryMovement> returnMovements = inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc("SALES_RETURN", invoice.getInvoiceNumber());
        assertThat(returnMovements).isEmpty();

        String returnReference = "CRN-" + invoice.getInvoiceNumber();
        assertThat(journalEntryRepository.findByCompanyAndReferenceNumber(company, returnReference)).isEmpty();
    }

    private void seedDispatchMovement() {
        seedDispatchMovement(new BigDecimal("1"), new BigDecimal("50.00"));
    }

    private void seedDispatchMovement(BigDecimal quantity, BigDecimal unitCost) {
        InventoryMovement dispatchMovement = new InventoryMovement();
        dispatchMovement.setFinishedGood(finishedGood);
        dispatchMovement.setReferenceType(InventoryReference.SALES_ORDER);
        dispatchMovement.setReferenceId(salesOrder.getId().toString());
        dispatchMovement.setMovementType("DISPATCH");
        dispatchMovement.setQuantity(quantity);
        dispatchMovement.setUnitCost(unitCost);
        inventoryMovementRepository.save(dispatchMovement);
    }

    private HttpHeaders authHeaders() {
        Map<String, Object> req = Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", req, Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) login.getBody().get("accessToken");
        assertThat(token).isNotBlank();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Company-Id", COMPANY_CODE);
        return h;
    }

    private Account ensureAccount(String code, String name, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setCompany(company);
                    account.setCode(code);
                    account.setName(name);
                    account.setType(type);
                    return accountRepository.save(account);
                });
    }

    private Dealer ensureDealer() {
        return dealerRepository.findByCompanyAndCodeIgnoreCase(company, "SRN-DEALER")
                .orElseGet(() -> {
                    Dealer created = new Dealer();
                    created.setCompany(company);
                    created.setCode("SRN-DEALER");
                    created.setName("Sales Return Dealer");
                    created.setReceivableAccount(ar);
                    created.setOutstandingBalance(BigDecimal.ZERO);
                    created.setCreditLimit(new BigDecimal("100000"));
                    return dealerRepository.save(created);
                });
    }

    private FinishedGood ensureFinishedGood() {
        return finishedGoodRepository.findByCompanyAndProductCode(company, "FG-SRN")
                .orElseGet(() -> {
                    FinishedGood fg = new FinishedGood();
                    fg.setCompany(company);
                    fg.setProductCode("FG-SRN");
                    fg.setName("Sales Return Product");
                    fg.setUnit("UNIT");
                    fg.setCostingMethod("FIFO");
                    fg.setValuationAccountId(inventory.getId());
                    fg.setCogsAccountId(cogs.getId());
                    fg.setRevenueAccountId(revenue.getId());
                    fg.setDiscountAccountId(discount.getId());
                    fg.setTaxAccountId(gstOutput.getId());
                    fg.setCurrentStock(BigDecimal.ZERO);
                    fg.setReservedStock(BigDecimal.ZERO);
                    return finishedGoodRepository.save(fg);
                });
    }

    private SalesOrder ensureSalesOrder() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-SRN-" + System.currentTimeMillis());
        order.setStatus("DISPATCHED");
        order.setSubtotalAmount(new BigDecimal("190.00"));
        order.setGstTotal(new BigDecimal("19.00"));
        order.setTotalAmount(new BigDecimal("209.00"));
        order.setGstRate(new BigDecimal("10.00"));
        return salesOrderRepository.save(order);
    }

    private Invoice ensureInvoice() {
        Invoice inv = new Invoice();
        inv.setCompany(company);
        inv.setDealer(dealer);
        inv.setSalesOrder(salesOrder);
        inv.setInvoiceNumber("SRN-INV-" + System.currentTimeMillis());
        inv.setStatus("POSTED");
        inv.setSubtotal(new BigDecimal("190.00"));
        inv.setTaxTotal(new BigDecimal("19.00"));
        inv.setTotalAmount(new BigDecimal("209.00"));
        inv.setOutstandingAmount(new BigDecimal("209.00"));
        inv.setIssueDate(LocalDate.now());
        inv.setDueDate(LocalDate.now().plusDays(14));

        InvoiceLine line = new InvoiceLine();
        line.setInvoice(inv);
        line.setProductCode(finishedGood.getProductCode());
        line.setQuantity(new BigDecimal("2.00"));
        line.setUnitPrice(new BigDecimal("100.00"));
        line.setDiscountAmount(new BigDecimal("10.00"));
        line.setTaxableAmount(new BigDecimal("190.00"));
        line.setTaxAmount(new BigDecimal("19.00"));
        line.setTaxRate(new BigDecimal("10.00"));
        line.setLineTotal(new BigDecimal("209.00"));
        inv.getLines().add(line);
        return invoiceRepository.save(inv);
    }

    private Invoice ensureInvoiceWithMixedDiscounts() {
        Invoice inv = new Invoice();
        inv.setCompany(company);
        inv.setDealer(dealer);
        inv.setSalesOrder(salesOrder);
        inv.setInvoiceNumber("SRN-INV-MIX-" + System.currentTimeMillis());
        inv.setStatus("POSTED");
        inv.setSubtotal(new BigDecimal("240.00"));
        inv.setTaxTotal(new BigDecimal("24.00"));
        inv.setTotalAmount(new BigDecimal("264.00"));
        inv.setOutstandingAmount(new BigDecimal("264.00"));
        inv.setIssueDate(LocalDate.now());
        inv.setDueDate(LocalDate.now().plusDays(14));

        InvoiceLine line1 = new InvoiceLine();
        line1.setInvoice(inv);
        line1.setProductCode(finishedGood.getProductCode());
        line1.setQuantity(new BigDecimal("2.00"));
        line1.setUnitPrice(new BigDecimal("100.00"));
        line1.setDiscountAmount(new BigDecimal("10.00"));
        line1.setTaxableAmount(new BigDecimal("190.00"));
        line1.setTaxAmount(new BigDecimal("19.00"));
        line1.setTaxRate(new BigDecimal("10.00"));
        line1.setLineTotal(new BigDecimal("209.00"));
        inv.getLines().add(line1);

        InvoiceLine line2 = new InvoiceLine();
        line2.setInvoice(inv);
        line2.setProductCode(finishedGood.getProductCode());
        line2.setQuantity(new BigDecimal("1.00"));
        line2.setUnitPrice(new BigDecimal("50.00"));
        line2.setDiscountAmount(BigDecimal.ZERO);
        line2.setTaxableAmount(new BigDecimal("50.00"));
        line2.setTaxAmount(new BigDecimal("5.00"));
        line2.setTaxRate(new BigDecimal("10.00"));
        line2.setLineTotal(new BigDecimal("55.00"));
        inv.getLines().add(line2);

        return invoiceRepository.save(inv);
    }

    private BigDecimal sumAccount(JournalEntry entry, Long accountId, boolean debit) {
        return entry.getLines().stream()
                .filter(line -> line.getAccount().getId().equals(accountId))
                .map(line -> debit
                        ? Optional.ofNullable(line.getDebit()).orElse(BigDecimal.ZERO)
                        : Optional.ofNullable(line.getCredit()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
