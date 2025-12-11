package com.bigbrightpaints.erp.core.service;

import com.bigbrightpaints.erp.core.domain.NumberSequenceRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class NumberSequenceServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NumberSequenceService numberSequenceService;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private NumberSequenceRepository numberSequenceRepository;

    @Test
    @Transactional
    void nextValueWorksWhenCompanyNotCommittedYet() {
        Company company = new Company();
        company.setName("Txn Seq Co");
        company.setCode("SEQ-" + UUID.randomUUID().toString().substring(0, 8));
        company.setTimezone("UTC");
        company = companyRepository.saveAndFlush(company);

        long value = numberSequenceService.nextValue(company, "TEST-SEQ");

        assertThat(value).isEqualTo(1L);
        assertThat(numberSequenceRepository.findByCompanyAndSequenceKey(company, "TEST-SEQ"))
                .isPresent()
                .get()
                .satisfies(seq -> assertThat(seq.getNextValue()).isEqualTo(2L));
    }

    @Test
    void nextValueIsMonotonicUnderContention() throws Exception {
        Company company = new Company();
        company.setName("Concurrent Seq Co");
        company.setCode("CONC-" + UUID.randomUUID().toString().substring(0, 8));
        company.setTimezone("UTC");
        final Company persistedCompany = companyRepository.saveAndFlush(company);

        final String key = "CONC-SEQ-" + UUID.randomUUID().toString().substring(0, 4);
        int calls = 20;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
                futures.add(executor.submit(() -> {
                    startGate.await(5, TimeUnit.SECONDS);
                    return numberSequenceService.nextValue(persistedCompany, key);
                }));
        }
        startGate.countDown();

        Set<Long> results = futures.stream()
                .map(f -> {
                    try {
                        return f.get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toSet());
        executor.shutdownNow();

        assertThat(results).hasSize(calls);
        assertThat(results).containsExactlyInAnyOrderElementsOf(
                java.util.stream.LongStream.rangeClosed(1, calls).boxed().toList());
    }
}
