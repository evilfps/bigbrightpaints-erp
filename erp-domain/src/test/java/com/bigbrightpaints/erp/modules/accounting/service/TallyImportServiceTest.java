package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.TallyImport;
import com.bigbrightpaints.erp.modules.accounting.domain.TallyImportRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.OpeningBalanceImportResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.TallyImportResponse;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.test.support.ReflectionFieldAccess;

@ExtendWith(MockitoExtension.class)
class TallyImportServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private AccountRepository accountRepository;
  @Mock private OpeningBalanceImportService openingBalanceImportService;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private TallyImportRepository tallyImportRepository;
  @Mock private AuditService auditService;

  private TallyImportService service;
  private Company company;

  @BeforeEach
  void setUp() {
    service =
        new TallyImportService(
            companyContextService,
            accountRepository,
            openingBalanceImportService,
            journalEntryRepository,
            tallyImportRepository,
            auditService,
            new ObjectMapper(),
            new ResourcelessTransactionManager());
    company = new Company();
    company.setCode("ACME");
    lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
  }

  @Test
  void importTallyXml_parsesLedgersAndOpeningVoucherRows_andReportsUnmappedGroups()
      throws Exception {
    MockMultipartFile file = xmlFile(sampleTallyXml());
    String fileHash = hashOf(file);

    when(tallyImportRepository.findByCompanyAndIdempotencyKey(eq(company), any()))
        .thenReturn(Optional.empty());
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), any()))
        .thenReturn(Optional.empty());
    when(tallyImportRepository.saveAndFlush(any(TallyImport.class)))
        .thenAnswer(
            invocation -> {
              TallyImport record = invocation.getArgument(0);
              ReflectionFieldAccess.setField(record, "id", 401L);
              return record;
            });
    when(tallyImportRepository.save(any(TallyImport.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Account debtors = account(11L, "CUSTOMER-A", "Customer A", AccountType.ASSET);
    Account creditors = account(12L, "SUPPLIER-B", "Supplier B", AccountType.LIABILITY);
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "CUSTOMER-A"))
        .thenReturn(Optional.of(debtors));
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "SUPPLIER-B"))
        .thenReturn(Optional.of(creditors));

    when(openingBalanceImportService.importFromParsedRows(any(List.class), any()))
        .thenReturn(OpeningBalanceImportResponse.fromSuccessfulRows(2, 0, List.of()));

    TallyImportResponse response = service.importTallyXml(file);

    assertThat(response.ledgersProcessed()).isEqualTo(3);
    assertThat(response.mappedLedgers()).isEqualTo(2);
    assertThat(response.accountsCreated()).isEqualTo(0);
    assertThat(response.openingVoucherEntriesProcessed()).isEqualTo(2);
    assertThat(response.openingBalanceRowsProcessed()).isEqualTo(2);
    assertThat(response.unmappedGroups()).containsExactly("Mystery Group");
    assertThat(response.unmappedItems()).isEmpty();
    assertThat(response.errors()).isEmpty();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<OpeningBalanceImportService.ParsedOpeningBalanceRow>> rowsCaptor =
        ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<String> referenceCaptor = ArgumentCaptor.forClass(String.class);
    verify(openingBalanceImportService)
        .importFromParsedRows(rowsCaptor.capture(), referenceCaptor.capture());
    assertThat(referenceCaptor.getValue()).startsWith("TALLY-OPEN-ACME-");
    assertThat(rowsCaptor.getValue())
        .extracting(OpeningBalanceImportService.ParsedOpeningBalanceRow::accountCode)
        .containsExactlyInAnyOrder("CUSTOMER-A", "SUPPLIER-B");

    verify(auditService).logSuccess(eq(AuditEvent.DATA_CREATE), any(Map.class));
    verify(tallyImportRepository).findByCompanyAndIdempotencyKey(company, fileHash);
  }

  @Test
  void importTallyXml_replaysByFileHashWithoutReprocessing() throws Exception {
    MockMultipartFile file = xmlFile(sampleTallyXml());
    String fileHash = hashOf(file);

    TallyImport existing = new TallyImport();
    existing.setCompany(company);
    existing.setIdempotencyKey(fileHash);
    existing.setIdempotencyHash(fileHash);
    existing.setLedgersProcessed(4);
    existing.setMappedLedgers(3);
    existing.setAccountsCreated(1);
    existing.setOpeningVoucherEntriesProcessed(2);
    existing.setOpeningBalanceRowsProcessed(2);
    existing.setUnmappedGroupsJson("[\"Unknown Group\"]");
    existing.setUnmappedItemsJson("[\"Mystery Ledger\"]");

    when(tallyImportRepository.findByCompanyAndIdempotencyKey(company, fileHash))
        .thenReturn(Optional.of(existing));

    TallyImportResponse replay = service.importTallyXml(file);

    assertThat(replay.ledgersProcessed()).isEqualTo(4);
    assertThat(replay.mappedLedgers()).isEqualTo(3);
    assertThat(replay.accountsCreated()).isEqualTo(1);
    assertThat(replay.unmappedGroups()).containsExactly("Unknown Group");
    assertThat(replay.unmappedItems()).containsExactly("Mystery Ledger");

    verify(openingBalanceImportService, never()).importFromParsedRows(any(List.class), any());
    verify(tallyImportRepository, never()).saveAndFlush(any(TallyImport.class));
  }

  @Test
  void importTallyXml_rejectsPayloadMismatchForExistingIdempotencyKey() {
    MockMultipartFile file = xmlFile(sampleTallyXml());

    TallyImport existing = new TallyImport();
    existing.setCompany(company);
    existing.setIdempotencyKey("same-key");
    existing.setIdempotencyHash("different-hash");

    when(tallyImportRepository.findByCompanyAndIdempotencyKey(eq(company), any()))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.importTallyXml(file))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
              assertThat(ex.getMessage())
                  .isEqualTo("Idempotency key already used with different payload");
            });

    verify(openingBalanceImportService, never()).importFromParsedRows(any(List.class), any());
  }

  @Test
  void importTallyXml_collectsUnmappedOpeningVoucherLedgerAsUnmappedItem() {
    MockMultipartFile file = xmlFile(xmlWithUnmappedOpeningVoucherLedger());

    when(tallyImportRepository.findByCompanyAndIdempotencyKey(eq(company), any()))
        .thenReturn(Optional.empty());
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), any()))
        .thenReturn(Optional.empty());
    when(tallyImportRepository.saveAndFlush(any(TallyImport.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(tallyImportRepository.save(any(TallyImport.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "KNOWN-LEDGER"))
        .thenReturn(Optional.of(account(51L, "KNOWN-LEDGER", "Known Ledger", AccountType.ASSET)));
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "UNMAPPED-VOUCHER"))
        .thenReturn(Optional.empty());

    when(openingBalanceImportService.importFromParsedRows(any(List.class), any()))
        .thenReturn(OpeningBalanceImportResponse.fromSuccessfulRows(1, 0, List.of()));

    TallyImportResponse response = service.importTallyXml(file);

    assertThat(response.openingVoucherEntriesProcessed()).isEqualTo(2);
    assertThat(response.openingBalanceRowsProcessed()).isEqualTo(1);
    assertThat(response.unmappedItems()).contains("Unmapped Voucher");
    assertThat(response.errors())
        .extracting(TallyImportResponse.ImportError::context)
        .contains("opening-row:Unmapped Voucher");
  }

  @Test
  void resolveFileHash_fallsBackToWeakFilenameHashWhenFileReadFails() {
    MultipartFile file =
        new MultipartFile() {
          @Override
          public String getName() {
            return "file";
          }

          @Override
          public String getOriginalFilename() {
            return "broken-tally.xml";
          }

          @Override
          public String getContentType() {
            return "application/xml";
          }

          @Override
          public boolean isEmpty() {
            return false;
          }

          @Override
          public long getSize() {
            return 1;
          }

          @Override
          public byte[] getBytes() throws IOException {
            throw new IOException("boom");
          }

          @Override
          public java.io.InputStream getInputStream() throws IOException {
            throw new IOException("boom");
          }

          @Override
          public void transferTo(java.io.File dest) throws IOException {
            throw new IOException("boom");
          }
        };

    String hash = ReflectionTestUtils.invokeMethod(service, "resolveFileHash", file);

    assertThat(hash).isEqualTo(Integer.toHexString("broken-tally.xml".hashCode()));
  }

  @Test
  void serializeStringList_returnsNullWhenObjectMapperWriteFails() {
    TallyImportService failingService = serviceWithObjectMapper(new WriteFailingObjectMapper());

    String serialized =
        ReflectionTestUtils.invokeMethod(failingService, "serializeStringList", List.of("alpha"));

    assertThat(serialized).isNull();
  }

  @Test
  void deserializeStringList_returnsEmptyWhenJsonIsInvalid() {
    List<String> parsed = ReflectionTestUtils.invokeMethod(service, "deserializeStringList", "{");

    assertThat(parsed).isEmpty();
  }

  @Test
  void serializeErrors_returnsNullWhenObjectMapperWriteFails() {
    TallyImportService failingService = serviceWithObjectMapper(new WriteFailingObjectMapper());

    String serialized =
        ReflectionTestUtils.invokeMethod(
            failingService,
            "serializeErrors",
            List.of(new TallyImportResponse.ImportError("row-1", "bad ledger")));

    assertThat(serialized).isNull();
  }

  @Test
  void deserializeErrors_returnsEmptyWhenJsonIsInvalid() {
    List<TallyImportResponse.ImportError> parsed =
        ReflectionTestUtils.invokeMethod(service, "deserializeErrors", "{");

    assertThat(parsed).isEmpty();
  }

  private Account account(Long id, String code, String name, AccountType type) {
    Account account = new Account();
    ReflectionFieldAccess.setField(account, "id", id);
    account.setCode(code);
    account.setName(name);
    account.setType(type);
    account.setCompany(company);
    return account;
  }

  private MockMultipartFile xmlFile(String xml) {
    return new MockMultipartFile(
        "file", "tally.xml", "application/xml", xml.getBytes(StandardCharsets.UTF_8));
  }

  private TallyImportService serviceWithObjectMapper(ObjectMapper objectMapper) {
    return new TallyImportService(
        companyContextService,
        accountRepository,
        openingBalanceImportService,
        journalEntryRepository,
        tallyImportRepository,
        auditService,
        objectMapper,
        new ResourcelessTransactionManager());
  }

  private String hashOf(MockMultipartFile file) throws Exception {
    return com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils.sha256Hex(file.getBytes());
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
                   <LEDGER NAME=\"Unknown G\">
                     <PARENT>Mystery Group</PARENT>
                   </LEDGER>
                 </TALLYMESSAGE>
                 <TALLYMESSAGE>
                   <VOUCHER VCHTYPE=\"Opening Balance\" VOUCHERTYPENAME=\"Opening Balance\">
                     <ALLLEDGERENTRIES.LIST>
                       <LEDGERNAME>Customer A</LEDGERNAME>
                       <AMOUNT>1500.00</AMOUNT>
                     </ALLLEDGERENTRIES.LIST>
                     <ALLLEDGERENTRIES.LIST>
                       <LEDGERNAME>Supplier B</LEDGERNAME>
                       <AMOUNT>-1500.00</AMOUNT>
                     </ALLLEDGERENTRIES.LIST>
                   </VOUCHER>
                 </TALLYMESSAGE>
               </DATA>
             </BODY>
           </ENVELOPE>
           """;
  }

  private String xmlWithUnmappedOpeningVoucherLedger() {
    return """
           <ENVELOPE>
             <BODY>
               <DATA>
                 <TALLYMESSAGE>
                   <LEDGER NAME=\"Known Ledger\">
                     <PARENT>Sundry Debtors</PARENT>
                   </LEDGER>
                 </TALLYMESSAGE>
                 <TALLYMESSAGE>
                   <VOUCHER VCHTYPE=\"Opening Balance\" VOUCHERTYPENAME=\"Opening Balance\">
                     <ALLLEDGERENTRIES.LIST>
                       <LEDGERNAME>Known Ledger</LEDGERNAME>
                       <AMOUNT>900.00</AMOUNT>
                     </ALLLEDGERENTRIES.LIST>
                     <ALLLEDGERENTRIES.LIST>
                       <LEDGERNAME>Unmapped Voucher</LEDGERNAME>
                       <PARENT>Unmapped Group</PARENT>
                       <AMOUNT>-900.00</AMOUNT>
                     </ALLLEDGERENTRIES.LIST>
                   </VOUCHER>
                 </TALLYMESSAGE>
               </DATA>
             </BODY>
           </ENVELOPE>
           """;
  }

  private static final class WriteFailingObjectMapper extends ObjectMapper {
    @Override
    public String writeValueAsString(Object value) throws JsonProcessingException {
      throw new JsonProcessingException("boom") {};
    }
  }
}
