package com.bigbrightpaints.erp.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class CompanyContextHolderTest {

  @Test
  void setGetClear_cycle() {
    CompanyContextHolder.setCompanyCode("ACME");
    assertThat(CompanyContextHolder.getCompanyCode()).isEqualTo("ACME");
    CompanyContextHolder.clear();
    assertThat(CompanyContextHolder.getCompanyCode()).isNull();
  }

  @Test
  void threadIsolation_doesNotLeakBetweenThreads() throws InterruptedException {
    CompanyContextHolder.setCompanyCode("MAIN");
    AtomicReference<String> workerValue = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              CompanyContextHolder.setCompanyCode("WORKER");
              workerValue.set(CompanyContextHolder.getCompanyCode());
              CompanyContextHolder.clear();
            });
    worker.start();
    worker.join();
    assertThat(workerValue.get()).isEqualTo("WORKER");
    assertThat(CompanyContextHolder.getCompanyCode()).isEqualTo("MAIN");
    CompanyContextHolder.clear();
  }
}
