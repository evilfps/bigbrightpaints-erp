package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.event.InventoryAccountingEventListener;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.event.InventoryMovementEvent;
import com.bigbrightpaints.erp.modules.inventory.event.InventoryValuationChangedEvent;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "erp.inventory.accounting.events.enabled=true")
class InventoryAccountingEventListenerIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "INV-EVT-01";
    private static final String REFERENCE = "INV-MOVE-TEST-1";

    @Autowired
    private InventoryAccountingEventListener listener;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    private Company company;
    private Account inventoryAccount;
    private Account cogsAccount;

    @BeforeEach
    void setUp() {
        company = dataSeeder.ensureCompany(COMPANY_CODE, "Inventory Events Co");
        CompanyContextHolder.setCompanyId(company.getCode());
        inventoryAccount = ensureAccount("INV-TEST", "Inventory", AccountType.ASSET);
        cogsAccount = ensureAccount("COGS-TEST", "COGS", AccountType.EXPENSE);
    }

    @AfterEach
    void tearDown() {
        CompanyContextHolder.clear();
    }

    @Test
    void movementEventSkipsWhenJournalAlreadyExists() {
        long before = journalEntryRepository.count();
        InventoryMovementEvent event = InventoryMovementEvent.builder()
                .companyId(company.getId())
                .movementType(InventoryMovementEvent.MovementType.ISSUE)
                .inventoryType(InventoryValuationChangedEvent.InventoryType.FINISHED_GOOD)
                .itemId(1L)
                .itemCode("FG-TEST")
                .itemName("Finished Good Test")
                .quantity(new BigDecimal("2"))
                .unitCost(new BigDecimal("5.00"))
                .totalCost(new BigDecimal("10.00"))
                .sourceAccountId(inventoryAccount.getId())
                .destinationAccountId(cogsAccount.getId())
                .referenceNumber(REFERENCE)
                .movementDate(LocalDate.of(2026, 1, 10))
                .memo("Test movement event")
                .relatedEntityId(99L)
                .relatedEntityType("TEST")
                .build();

        listener.onInventoryMovement(event);
        long afterFirst = journalEntryRepository.count();
        assertThat(afterFirst).isEqualTo(before + 1);

        listener.onInventoryMovement(event);
        long afterSecond = journalEntryRepository.count();
        assertThat(afterSecond).isEqualTo(afterFirst);
    }

    @Test
    void movementEventsWithSameReferenceButDifferentItemsPostSeparately() {
        long before = journalEntryRepository.count();
        InventoryMovementEvent first = InventoryMovementEvent.builder()
                .companyId(company.getId())
                .movementType(InventoryMovementEvent.MovementType.ISSUE)
                .inventoryType(InventoryValuationChangedEvent.InventoryType.FINISHED_GOOD)
                .itemId(1L)
                .itemCode("FG-TEST-1")
                .itemName("Finished Good Test 1")
                .quantity(new BigDecimal("2"))
                .unitCost(new BigDecimal("5.00"))
                .totalCost(new BigDecimal("10.00"))
                .sourceAccountId(inventoryAccount.getId())
                .destinationAccountId(cogsAccount.getId())
                .referenceNumber(REFERENCE)
                .movementDate(LocalDate.of(2026, 1, 10))
                .memo("Test movement event 1")
                .relatedEntityId(99L)
                .relatedEntityType("TEST")
                .build();

        InventoryMovementEvent second = InventoryMovementEvent.builder()
                .companyId(company.getId())
                .movementType(InventoryMovementEvent.MovementType.ISSUE)
                .inventoryType(InventoryValuationChangedEvent.InventoryType.FINISHED_GOOD)
                .itemId(2L)
                .itemCode("FG-TEST-2")
                .itemName("Finished Good Test 2")
                .quantity(new BigDecimal("3"))
                .unitCost(new BigDecimal("4.00"))
                .totalCost(new BigDecimal("12.00"))
                .sourceAccountId(inventoryAccount.getId())
                .destinationAccountId(cogsAccount.getId())
                .referenceNumber(REFERENCE)
                .movementDate(LocalDate.of(2026, 1, 10))
                .memo("Test movement event 2")
                .relatedEntityId(100L)
                .relatedEntityType("TEST")
                .build();

        listener.onInventoryMovement(first);
        listener.onInventoryMovement(second);
        long after = journalEntryRepository.count();
        assertThat(after).isEqualTo(before + 2);
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
}
