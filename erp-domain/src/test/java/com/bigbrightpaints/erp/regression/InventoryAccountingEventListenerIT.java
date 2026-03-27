package com.bigbrightpaints.erp.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.event.InventoryAccountingEventListener;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.event.InventoryMovementEvent;
import com.bigbrightpaints.erp.modules.inventory.event.InventoryValuationChangedEvent;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@TestPropertySource(properties = "erp.inventory.accounting.events.enabled=true")
@Tag("critical")
class InventoryAccountingEventListenerIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "INV-EVT-01";
  private static final String REFERENCE = "INV-MOVE-TEST-1";

  @Autowired private InventoryAccountingEventListener listener;

  @Autowired private AccountRepository accountRepository;

  @Autowired private JournalEntryRepository journalEntryRepository;

  private Company company;
  private Account inventoryAccount;
  private Account cogsAccount;

  @BeforeEach
  void setUp() {
    company = dataSeeder.ensureCompany(COMPANY_CODE, "Inventory Events Co");
    CompanyContextHolder.setCompanyCode(company.getCode());
    inventoryAccount = ensureAccount("INV-TEST", "Inventory", AccountType.ASSET);
    cogsAccount = ensureAccount("COGS-TEST", "COGS", AccountType.EXPENSE);
  }

  @AfterEach
  void tearDown() {
    CompanyContextHolder.clear();
  }

  @Test
  void movementEventSkipsWhenJournalAlreadyExists() {
    LocalDate movementDate = LocalDate.now().minusDays(1);
    long before = journalEntryRepository.count();
    InventoryMovementEvent event =
        InventoryMovementEvent.builder()
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
            .movementDate(movementDate)
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
    LocalDate movementDate = LocalDate.now().minusDays(1);
    long before = journalEntryRepository.count();
    InventoryMovementEvent first =
        InventoryMovementEvent.builder()
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
            .movementDate(movementDate)
            .memo("Test movement event 1")
            .relatedEntityId(99L)
            .relatedEntityType("TEST")
            .build();

    InventoryMovementEvent second =
        InventoryMovementEvent.builder()
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
            .movementDate(movementDate)
            .memo("Test movement event 2")
            .relatedEntityId(100L)
            .relatedEntityType("TEST")
            .build();

    listener.onInventoryMovement(first);
    listener.onInventoryMovement(second);
    long after = journalEntryRepository.count();
    assertThat(after).isEqualTo(before + 2);
  }

  @Test
  void movementEventSkipsWhenAccountsAreMissing() {
    LocalDate movementDate = LocalDate.now().minusDays(1);
    long before = journalEntryRepository.count();
    InventoryMovementEvent event =
        InventoryMovementEvent.builder()
            .companyId(company.getId())
            .movementType(InventoryMovementEvent.MovementType.ISSUE)
            .inventoryType(InventoryValuationChangedEvent.InventoryType.FINISHED_GOOD)
            .itemId(3L)
            .itemCode("FG-TEST-3")
            .itemName("Finished Good Test 3")
            .quantity(new BigDecimal("1"))
            .unitCost(new BigDecimal("8.00"))
            .totalCost(new BigDecimal("8.00"))
            .sourceAccountId(inventoryAccount.getId())
            .destinationAccountId(null)
            .referenceNumber("INV-MOVE-NO-ACCOUNTS")
            .movementDate(movementDate)
            .memo("Test movement event without destination account")
            .relatedEntityId(101L)
            .relatedEntityType("TEST")
            .build();

    listener.onInventoryMovement(event);

    assertThat(journalEntryRepository.count()).isEqualTo(before);
  }

  @Test
  void movementEventSkipsCanonicalGoodsReceiptBoundaryEvenWhenAccountsArePresent() {
    LocalDate movementDate = LocalDate.now().minusDays(1);
    long before = journalEntryRepository.count();
    InventoryMovementEvent event =
        InventoryMovementEvent.builder()
            .companyId(company.getId())
            .movementType(InventoryMovementEvent.MovementType.RECEIPT)
            .inventoryType(InventoryValuationChangedEvent.InventoryType.RAW_MATERIAL)
            .itemId(4L)
            .itemCode("RM-GRN-1")
            .itemName("Raw Material Test")
            .quantity(new BigDecimal("4"))
            .unitCost(new BigDecimal("5.00"))
            .totalCost(new BigDecimal("20.00"))
            .sourceAccountId(cogsAccount.getId())
            .destinationAccountId(inventoryAccount.getId())
            .referenceNumber("GRN-TEST-001")
            .movementDate(movementDate)
            .memo("Goods receipt event")
            .relatedEntityId(401L)
            .relatedEntityType(InventoryReference.GOODS_RECEIPT)
            .build();

    listener.onInventoryMovement(event);

    assertThat(journalEntryRepository.count()).isEqualTo(before);
  }

  @Test
  void movementEventSkipsCanonicalSalesDispatchBoundaryEvenWhenAccountsArePresent() {
    LocalDate movementDate = LocalDate.now().minusDays(1);
    long before = journalEntryRepository.count();
    InventoryMovementEvent event =
        InventoryMovementEvent.builder()
            .companyId(company.getId())
            .movementType(InventoryMovementEvent.MovementType.ISSUE)
            .inventoryType(InventoryValuationChangedEvent.InventoryType.FINISHED_GOOD)
            .itemId(5L)
            .itemCode("FG-DISPATCH-1")
            .itemName("Finished Good Dispatch Test")
            .quantity(new BigDecimal("2"))
            .unitCost(new BigDecimal("11.00"))
            .totalCost(new BigDecimal("22.00"))
            .sourceAccountId(inventoryAccount.getId())
            .destinationAccountId(cogsAccount.getId())
            .referenceNumber("SALES_ORDER-501")
            .movementDate(movementDate)
            .memo("Dispatch event")
            .relatedEntityId(501L)
            .relatedEntityType(InventoryReference.SALES_ORDER)
            .build();

    listener.onInventoryMovement(event);

    assertThat(journalEntryRepository.count()).isEqualTo(before);
  }

  @Test
  void movementEventSkipsCanonicalPackagingSlipBoundaryEvenWhenAccountsArePresent() {
    LocalDate movementDate = LocalDate.now().minusDays(1);
    long before = journalEntryRepository.count();
    InventoryMovementEvent event =
        InventoryMovementEvent.builder()
            .companyId(company.getId())
            .movementType(InventoryMovementEvent.MovementType.ISSUE)
            .inventoryType(InventoryValuationChangedEvent.InventoryType.FINISHED_GOOD)
            .itemId(6L)
            .itemCode("FG-PS-1")
            .itemName("Finished Good Packaging Slip Test")
            .quantity(new BigDecimal("1"))
            .unitCost(new BigDecimal("9.00"))
            .totalCost(new BigDecimal("9.00"))
            .sourceAccountId(inventoryAccount.getId())
            .destinationAccountId(cogsAccount.getId())
            .referenceNumber("PS-601")
            .movementDate(movementDate)
            .memo("Packaging slip event")
            .relatedEntityId(601L)
            .relatedEntityType("PACKAGING_SLIP")
            .build();

    listener.onInventoryMovement(event);

    assertThat(journalEntryRepository.count()).isEqualTo(before);
  }

  @Test
  void movementEventPostsWhenRelatedEntityTypeIsBlank() {
    LocalDate movementDate = LocalDate.now().minusDays(1);
    long before = journalEntryRepository.count();
    InventoryMovementEvent event =
        InventoryMovementEvent.builder()
            .companyId(company.getId())
            .movementType(InventoryMovementEvent.MovementType.ISSUE)
            .inventoryType(InventoryValuationChangedEvent.InventoryType.FINISHED_GOOD)
            .itemId(7L)
            .itemCode("FG-BLANK-REL")
            .itemName("Finished Good Blank Relation")
            .quantity(new BigDecimal("1"))
            .unitCost(new BigDecimal("13.00"))
            .totalCost(new BigDecimal("13.00"))
            .sourceAccountId(inventoryAccount.getId())
            .destinationAccountId(cogsAccount.getId())
            .referenceNumber("INV-MOVE-BLANK-REL")
            .movementDate(movementDate)
            .memo("Blank related entity type should not be treated as canonical")
            .relatedEntityId(701L)
            .relatedEntityType("   ")
            .build();

    listener.onInventoryMovement(event);

    assertThat(journalEntryRepository.count()).isEqualTo(before + 1);
  }

  @Test
  void movementEventPostsWhenRelatedEntityTypeIsNull() {
    LocalDate movementDate = LocalDate.now().minusDays(1);
    long before = journalEntryRepository.count();
    InventoryMovementEvent event =
        InventoryMovementEvent.builder()
            .companyId(company.getId())
            .movementType(InventoryMovementEvent.MovementType.ISSUE)
            .inventoryType(InventoryValuationChangedEvent.InventoryType.FINISHED_GOOD)
            .itemId(8L)
            .itemCode("FG-NULL-REL")
            .itemName("Finished Good Null Relation")
            .quantity(new BigDecimal("1"))
            .unitCost(new BigDecimal("14.00"))
            .totalCost(new BigDecimal("14.00"))
            .sourceAccountId(inventoryAccount.getId())
            .destinationAccountId(cogsAccount.getId())
            .referenceNumber("INV-MOVE-NULL-REL")
            .movementDate(movementDate)
            .memo("Null related entity type should not be treated as canonical")
            .relatedEntityId(801L)
            .relatedEntityType(null)
            .build();

    listener.onInventoryMovement(event);

    assertThat(journalEntryRepository.count()).isEqualTo(before + 1);
  }

  @Test
  void valuationEventSkipsWhenValueChangeIsZero() {
    long before = journalEntryRepository.count();
    InventoryValuationChangedEvent event =
        InventoryValuationChangedEvent.builder()
            .companyId(company.getId())
            .inventoryType(InventoryValuationChangedEvent.InventoryType.RAW_MATERIAL)
            .itemId(7L)
            .itemCode("RM-VAL-0")
            .itemName("Raw Material Zero")
            .oldValue(new BigDecimal("12.00"))
            .newValue(new BigDecimal("12.00"))
            .quantity(new BigDecimal("3"))
            .oldUnitCost(new BigDecimal("4.00"))
            .newUnitCost(new BigDecimal("4.00"))
            .inventoryAccountId(inventoryAccount.getId())
            .reason(InventoryValuationChangedEvent.ValuationChangeReason.PHYSICAL_COUNT_ADJUSTMENT)
            .build();

    listener.onInventoryValuationChanged(event);

    assertThat(journalEntryRepository.count()).isEqualTo(before);
  }

  private Account ensureAccount(String code, String name, AccountType type) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account account = new Account();
              account.setCompany(company);
              account.setCode(code);
              account.setName(name);
              account.setType(type);
              return accountRepository.save(account);
            });
  }
}
