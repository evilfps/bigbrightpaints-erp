package com.bigbrightpaints.erp.core.auditaccess;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    Long platformCompanyId =
        companyRepository
            .findIdByCodeIgnoreCase(authScopeService.getPlatformScopeCode())
            .orElse(null);

    return (root, query, cb) -> {
      List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
      predicates.add(cb.isNull(root.get("companyId")));
      predicates.add(pathEqualsOrStartsWith(root.get("requestPath"), cb, "/api/v1/superadmin"));
      predicates.add(pathEqualsOrStartsWith(root.get("requestPath"), cb, "/api/v1/admin/settings"));
      predicates.add(pathEqualsOrStartsWith(root.get("requestPath"), cb, "/api/v1/companies"));
      if (platformCompanyId != null) {
        predicates.add(cb.equal(root.get("companyId"), platformCompanyId));
      }
      return cb.or(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
    };
  }

  public Map<Long, String> resolveCompanyCodes(Collection<Long> companyIds) {
    if (companyIds == null || companyIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, String> companyCodes = new LinkedHashMap<>();
    for (CompanyRepository.CompanyCodeProjection projection :
        companyRepository.findCompanyCodesByIdIn(companyIds)) {
      if (projection.getId() == null || !StringUtils.hasText(projection.getCode())) {
        continue;
      }
      companyCodes.put(projection.getId(), projection.getCode().trim());
    }
    return companyCodes;
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
