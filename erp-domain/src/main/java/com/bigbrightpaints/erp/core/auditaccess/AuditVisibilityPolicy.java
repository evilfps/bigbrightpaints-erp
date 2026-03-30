package com.bigbrightpaints.erp.core.auditaccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;

@Component
public class AuditVisibilityPolicy {

  private final CompanyRepository companyRepository;
  private final AuthScopeService authScopeService;

  public AuditVisibilityPolicy(
      CompanyRepository companyRepository, AuthScopeService authScopeService) {
    this.companyRepository = companyRepository;
    this.authScopeService = authScopeService;
  }

  public Specification<AuditLog> tenantCompanyVisibility(Long companyId) {
    return (root, query, cb) -> cb.equal(root.get("companyId"), companyId);
  }

  public Specification<AuditLog> platformVisibility() {
    Set<Long> platformCompanyIds =
        companyRepository.findAll().stream()
            .filter(company -> StringUtils.hasText(company.getCode()))
            .filter(company -> authScopeService.isPlatformScope(company.getCode()))
            .map(company -> company.getId())
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());

    return (root, query, cb) -> {
      List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
      predicates.add(cb.isNull(root.get("companyId")));
      predicates.add(pathEqualsOrStartsWith(root.get("requestPath"), cb, "/api/v1/superadmin"));
      predicates.add(pathEqualsOrStartsWith(root.get("requestPath"), cb, "/api/v1/admin/settings"));
      predicates.add(pathEqualsOrStartsWith(root.get("requestPath"), cb, "/api/v1/companies"));
      if (!platformCompanyIds.isEmpty()) {
        predicates.add(root.get("companyId").in(platformCompanyIds));
      }
      return cb.or(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
    };
  }

  public String resolveCompanyCode(Long companyId) {
    if (companyId == null) {
      return null;
    }
    return companyRepository.findById(companyId).map(company -> company.getCode()).orElse(null);
  }

  public boolean isAccountingModule(String module) {
    return "ACCOUNTING".equalsIgnoreCase(module);
  }

  private jakarta.persistence.criteria.Predicate pathEqualsOrStartsWith(
      jakarta.persistence.criteria.Path<String> path,
      jakarta.persistence.criteria.CriteriaBuilder cb,
      String prefix) {
    return cb.or(cb.equal(path, prefix), cb.like(path, prefix + "/%"));
  }
}
