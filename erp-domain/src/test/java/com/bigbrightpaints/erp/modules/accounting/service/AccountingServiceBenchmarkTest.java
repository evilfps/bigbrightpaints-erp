package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalLineDto;

@ExtendWith(MockitoExtension.class)
class AccountingServiceBenchmarkTest {

  private static final LocalDate TODAY = LocalDate.of(2025, 12, 15);

  @Mock private AccountResolutionOwnerService accountResolutionOwnerService;
  @Mock private JournalEntryService journalEntryService;
  @Mock private DealerReceiptService dealerReceiptService;
  @Mock private SettlementService settlementService;
  @Mock private CreditDebitNoteService creditDebitNoteService;
  @Mock private InventoryAccountingService inventoryAccountingService;

  private AccountingService accountingService;

  @BeforeEach
  void setup() {
    accountingService =
        new AccountingService(
            accountResolutionOwnerService,
            journalEntryService,
            dealerReceiptService,
            settlementService,
            creditDebitNoteService,
            inventoryAccountingService);

    AtomicLong ids = new AtomicLong(1);
    when(journalEntryService.createJournalEntry(any()))
        .thenAnswer(
            invocation -> {
              JournalEntryRequest request = invocation.getArgument(0);
              return new JournalEntryDto(
                  ids.getAndIncrement(),
                  null,
                  request.referenceNumber(),
                  request.entryDate(),
                  request.memo(),
                  "POSTED",
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  request.lines() == null
                      ? List.<JournalLineDto>of()
                      : request.lines().stream()
                          .map(
                              line ->
                                  new JournalLineDto(
                                      line.accountId(),
                                      null,
                                      line.description(),
                                      line.debit(),
                                      line.credit()))
                          .toList(),
                  null,
                  null,
                  null,
                  null,
                  null,
                  null);
            });
  }

  @Test
  void benchmark_createJournalEntry_twoLinePosting() {
    Assumptions.assumeTrue(
        Boolean.getBoolean("runBenchmarks"), "Set -DrunBenchmarks=true to enable benchmarks");

    int warmupIterations = Integer.getInteger("benchWarmup", 500);
    int iterations = Integer.getInteger("benchIterations", 5000);
    BigDecimal amount = new BigDecimal("123.45");

    for (int i = 0; i < warmupIterations; i++) {
      accountingService.createJournalEntry(twoLineRequest("WARM-2L-" + i, amount));
    }

    long start = System.nanoTime();
    JournalEntryDto last = null;
    for (int i = 0; i < iterations; i++) {
      last = accountingService.createJournalEntry(twoLineRequest("BENCH-2L-" + i, amount));
    }
    long elapsedNanos = System.nanoTime() - start;

    double seconds = elapsedNanos / 1_000_000_000.0;
    double opsPerSecond = iterations / seconds;
    double msPerOp = (seconds * 1_000.0) / iterations;
    System.out.printf(
        "AccountingService.createJournalEntry (2 lines): %,d ops in %.3fs => %.0f ops/s (%.3f"
            + " ms/op)%n",
        iterations, seconds, opsPerSecond, msPerOp);

    assertThat(last).isNotNull();
    assertThat(last.lines()).hasSize(2);
  }

  @Test
  void benchmark_createJournalEntry_fiftyLinePosting() {
    Assumptions.assumeTrue(
        Boolean.getBoolean("runBenchmarks"), "Set -DrunBenchmarks=true to enable benchmarks");

    int warmupIterations = Integer.getInteger("benchWarmupLarge", 100);
    int iterations = Integer.getInteger("benchIterationsLarge", 1000);
    BigDecimal amount = new BigDecimal("10.00");
    List<JournalEntryRequest.JournalLineRequest> lines = fiftyLineBalanced(amount);

    for (int i = 0; i < warmupIterations; i++) {
      accountingService.createJournalEntry(multiLineRequest("WARM-50L-" + i, lines));
    }

    long start = System.nanoTime();
    JournalEntryDto last = null;
    for (int i = 0; i < iterations; i++) {
      last = accountingService.createJournalEntry(multiLineRequest("BENCH-50L-" + i, lines));
    }
    long elapsedNanos = System.nanoTime() - start;

    double seconds = elapsedNanos / 1_000_000_000.0;
    double opsPerSecond = iterations / seconds;
    double msPerOp = (seconds * 1_000.0) / iterations;
    System.out.printf(
        "AccountingService.createJournalEntry (50 lines): %,d ops in %.3fs => %.0f ops/s (%.3f"
            + " ms/op)%n",
        iterations, seconds, opsPerSecond, msPerOp);

    assertThat(last).isNotNull();
    assertThat(last.lines()).hasSize(50);
  }

  private static JournalEntryRequest twoLineRequest(String reference, BigDecimal amount) {
    return new JournalEntryRequest(
        reference,
        TODAY,
        "Benchmark posting",
        null,
        null,
        Boolean.FALSE,
        List.of(
            new JournalEntryRequest.JournalLineRequest(1L, "Dr", amount, BigDecimal.ZERO),
            new JournalEntryRequest.JournalLineRequest(2L, "Cr", BigDecimal.ZERO, amount)));
  }

  private static JournalEntryRequest multiLineRequest(
      String reference, List<JournalEntryRequest.JournalLineRequest> lines) {
    return new JournalEntryRequest(reference, TODAY, "Benchmark posting", null, null, false, lines);
  }

  private static List<JournalEntryRequest.JournalLineRequest> fiftyLineBalanced(BigDecimal amount) {
    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
    for (int i = 0; i < 25; i++) {
      lines.add(
          new JournalEntryRequest.JournalLineRequest((long) i + 1, "Dr", amount, BigDecimal.ZERO));
    }
    for (int i = 0; i < 25; i++) {
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              (long) i + 101, "Cr", BigDecimal.ZERO, amount));
    }
    return lines;
  }
}
