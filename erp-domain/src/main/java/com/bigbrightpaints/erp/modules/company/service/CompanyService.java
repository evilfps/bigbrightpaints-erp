package com.bigbrightpaints.erp.modules.company.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyRequest;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CompanyService {

    private final CompanyRepository repository;

    public CompanyService(CompanyRepository repository) {
        this.repository = repository;
    }

    public List<CompanyDto> findAll() {
        return repository.findAll().stream().map(this::toDto).toList();
    }

    public CompanyDto create(CompanyRequest request) {
        Company company = new Company();
        company.setName(request.name());
        company.setCode(request.code());
        company.setTimezone(request.timezone());
        company.setDefaultGstRate(request.defaultGstRate());
        return toDto(repository.save(company));
    }

    @Transactional
    public CompanyDto update(Long id, CompanyRequest request) {
        Company company = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Company not found"));
        company.setName(request.name());
        company.setCode(request.code());
        company.setTimezone(request.timezone());
        company.setDefaultGstRate(request.defaultGstRate());
        return toDto(company);
    }

    public void delete(Long id) {
        repository.deleteById(id);
    }

    public Company findByCode(String code) {
        return repository.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + code));
    }

    private CompanyDto toDto(Company company) {
        return new CompanyDto(
                company.getId(),
                company.getPublicId(),
                company.getName(),
                company.getCode(),
                company.getTimezone(),
                company.getDefaultGstRate());
    }
}
