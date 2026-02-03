package com.bigbrightpaints.erp.modules.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyRequest;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {

    @Mock
    private CompanyRepository repository;

    private CompanyService companyService;

    @BeforeEach
    void setUp() {
        companyService = new CompanyService(repository);
    }

    @Test
    void switchCompany_deniesWhenNotMember() {
        Company allowed = company(1L, "ACME");

        assertThatThrownBy(() -> companyService.switchCompany("BBP", Set.of(allowed)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Not allowed");

        verify(repository, never()).findByCodeIgnoreCase("BBP");
    }

    @Test
    void switchCompany_returnsDtoWhenMember() {
        Company allowed = company(1L, "ACME");
        when(repository.findByCodeIgnoreCase("acme")).thenReturn(Optional.of(allowed));

        CompanyDto dto = companyService.switchCompany("acme", Set.of(allowed));

        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.code()).isEqualTo("ACME");
    }

    @Test
    void update_deniesWhenNotMember() {
        Company allowed = company(1L, "ACME");
        CompanyRequest request = new CompanyRequest("New Name", "NEW", "UTC", BigDecimal.TEN);

        assertThatThrownBy(() -> companyService.update(2L, request, Set.of(allowed)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Not allowed");

        verify(repository, never()).findById(anyLong());
    }

    @Test
    void update_allowsMember() {
        Company allowed = company(1L, "ACME");
        CompanyRequest request = new CompanyRequest("New Name", "NEW", "UTC", BigDecimal.TEN);
        when(repository.findById(1L)).thenReturn(Optional.of(allowed));

        CompanyDto dto = companyService.update(1L, request, Set.of(allowed));

        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.name()).isEqualTo("New Name");
        assertThat(dto.code()).isEqualTo("NEW");
    }

    @Test
    void delete_deniesWhenNotMember() {
        Company allowed = company(1L, "ACME");

        assertThatThrownBy(() -> companyService.delete(2L, Set.of(allowed)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Not allowed");

        verify(repository, never()).deleteById(anyLong());
    }

    private Company company(Long id, String code) {
        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", id);
        ReflectionTestUtils.setField(company, "publicId", UUID.randomUUID());
        company.setName("Company " + code);
        company.setCode(code);
        company.setTimezone("UTC");
        company.setDefaultGstRate(BigDecimal.TEN);
        return company;
    }
}
