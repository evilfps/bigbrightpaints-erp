package com.bigbrightpaints.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.sales.domain.OrderSequence;
import com.bigbrightpaints.erp.modules.sales.domain.OrderSequenceRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderNumberServiceTest {

    @Mock
    private OrderSequenceRepository orderSequenceRepository;
    @Mock
    private AuditService auditService;

    private OrderNumberService orderNumberService;

    @BeforeEach
    void setup() {
        orderNumberService = new OrderNumberService(orderSequenceRepository, auditService);
        when(orderSequenceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void generatesOrderNumbersPerCompany() {
        Company first = new Company();
        first.setCode("C1");
        first.setTimezone("UTC");
        Company second = new Company();
        second.setCode("C2");
        second.setTimezone("UTC");

        OrderSequence seq1 = new OrderSequence();
        OrderSequence seq2 = new OrderSequence();
        when(orderSequenceRepository.findByCompanyAndFiscalYear(eq(first), anyInt())).thenReturn(Optional.of(seq1));
        when(orderSequenceRepository.findByCompanyAndFiscalYear(eq(second), anyInt())).thenReturn(Optional.of(seq2));

        String orderNumber1 = orderNumberService.nextOrderNumber(first);
        String orderNumber2 = orderNumberService.nextOrderNumber(second);

        assertThat(orderNumber1).startsWith("C1-");
        assertThat(orderNumber2).startsWith("C2-");
        assertThat(orderNumber1).isNotEqualTo(orderNumber2);
        verify(orderSequenceRepository).save(seq1);
        verify(orderSequenceRepository).save(seq2);
        verify(auditService, times(2)).logSuccess(eq(AuditEvent.ORDER_NUMBER_GENERATED), anyMap());
    }
}
