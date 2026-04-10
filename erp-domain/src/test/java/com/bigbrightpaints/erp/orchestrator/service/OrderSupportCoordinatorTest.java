package com.bigbrightpaints.erp.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionPlanRequest;
import com.bigbrightpaints.erp.modules.factory.service.FactoryService;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.orchestrator.config.OrchestratorFeatureFlags;
import com.bigbrightpaints.erp.orchestrator.repository.OrderAutoApprovalState;
import com.bigbrightpaints.erp.orchestrator.repository.OrderAutoApprovalStateRepository;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class OrderSupportCoordinatorTest {

  @Mock private SalesService salesService;
  @Mock private FactoryService factoryService;
  @Mock private OrderAutoApprovalStateRepository orderAutoApprovalStateRepository;
  @Mock private CompanyClock companyClock;
  @Mock private CompanyRepository companyRepository;

  private OrderSupportCoordinator coordinator;
  private Company company;

  @BeforeEach
  void setUp() {
    coordinator =
        new OrderSupportCoordinator(
            salesService,
            factoryService,
            orderAutoApprovalStateRepository,
            companyClock,
            new OrchestratorFeatureFlags(true, true),
            new IntegrationCoordinatorSupportService(companyRepository),
            new NoOpTransactionManager());
    company = new Company();
    company.setCode("COMP");
    company.setTimezone("UTC");
  }

  @Test
  void queueProduction_createsPlanForRequestedOrder() {
    when(companyRepository.findByCodeIgnoreCase("COMP")).thenReturn(Optional.of(company));
    when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 4, 10));

    coordinator.queueProduction("42", "COMP");

    ArgumentCaptor<ProductionPlanRequest> requestCaptor =
        ArgumentCaptor.forClass(ProductionPlanRequest.class);
    verify(factoryService).createPlan(requestCaptor.capture());
    assertThat(requestCaptor.getValue().planNumber()).isEqualTo("PLAN-42");
    assertThat(requestCaptor.getValue().plannedDate()).isEqualTo(LocalDate.of(2026, 4, 11));
    assertThat(requestCaptor.getValue().notes()).isEqualTo("Auto-generated from orchestrator");
  }

  @Test
  void lockAutoApprovalState_retriesFetchAfterDuplicateInsert() {
    OrderAutoApprovalState state = new OrderAutoApprovalState("COMP", 42L);
    when(orderAutoApprovalStateRepository.findByCompanyCodeAndOrderId("COMP", 42L))
        .thenReturn(Optional.empty(), Optional.of(state));
    when(orderAutoApprovalStateRepository.save(any(OrderAutoApprovalState.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate"));

    OrderAutoApprovalState result = coordinator.lockAutoApprovalState("COMP", 42L);

    assertThat(result).isSameAs(state);
    verify(orderAutoApprovalStateRepository).save(any(OrderAutoApprovalState.class));
  }

  private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
