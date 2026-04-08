package com.bigbrightpaints.erp.modules.sales.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.modules.company.domain.Company;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

@Repository
class SalesOrderSearchRepositoryImpl implements SalesOrderSearchRepository {

  @PersistenceContext private EntityManager entityManager;

  @Override
  public Page<Long> searchIdsByCompany(
      Company company,
      String status,
      Dealer dealer,
      String orderNumber,
      Instant fromDate,
      Instant toDate,
      Pageable pageable) {
    CriteriaBuilder cb = entityManager.getCriteriaBuilder();

    CriteriaQuery<Long> selectQuery = cb.createQuery(Long.class);
    Root<SalesOrder> selectRoot = selectQuery.from(SalesOrder.class);
    selectQuery
        .select(selectRoot.get("id"))
        .where(
            buildPredicates(company, status, dealer, orderNumber, fromDate, toDate, selectRoot, cb)
                .toArray(Predicate[]::new))
        .orderBy(cb.desc(selectRoot.get("createdAt")), cb.desc(selectRoot.get("id")));

    TypedQuery<Long> typedSelectQuery = entityManager.createQuery(selectQuery);
    if (pageable.isPaged()) {
      typedSelectQuery.setFirstResult((int) pageable.getOffset());
      typedSelectQuery.setMaxResults(pageable.getPageSize());
    }
    List<Long> ids = typedSelectQuery.getResultList();

    CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
    Root<SalesOrder> countRoot = countQuery.from(SalesOrder.class);
    countQuery
        .select(cb.count(countRoot))
        .where(
            buildPredicates(company, status, dealer, orderNumber, fromDate, toDate, countRoot, cb)
                .toArray(Predicate[]::new));

    long total = entityManager.createQuery(countQuery).getSingleResult();
    return new PageImpl<>(ids, pageable, total);
  }

  private List<Predicate> buildPredicates(
      Company company,
      String status,
      Dealer dealer,
      String orderNumber,
      Instant fromDate,
      Instant toDate,
      Root<SalesOrder> root,
      CriteriaBuilder cb) {
    List<Predicate> predicates = new ArrayList<>();
    predicates.add(cb.equal(root.get("company"), company));
    predicates.add(
        cb.notEqual(cb.upper(cb.trim(cb.coalesce(root.get("status"), cb.literal("")))), "DELETED"));

    Predicate statusPredicate = buildStatusPredicate(root.get("status"), cb, status);
    if (statusPredicate != null) {
      predicates.add(statusPredicate);
    }
    if (dealer != null) {
      predicates.add(cb.equal(root.get("dealer"), dealer));
    }
    if (StringUtils.hasText(orderNumber)) {
      predicates.add(
          cb.like(
              cb.lower(root.get("orderNumber")),
              containsPattern(orderNumber.trim().toLowerCase(Locale.ROOT)),
              '\\'));
    }
    if (fromDate != null) {
      predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromDate));
    }
    if (toDate != null) {
      predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), toDate));
    }
    return predicates;
  }

  private Predicate buildStatusPredicate(
      Path<String> statusPath, CriteriaBuilder cb, String status) {
    if (!StringUtils.hasText(status)) {
      return null;
    }
    String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
    jakarta.persistence.criteria.Expression<String> normalizedPath = cb.upper(cb.trim(statusPath));
    return switch (normalizedStatus) {
      case "DRAFT" ->
          cb.or(cb.equal(normalizedPath, normalizedStatus), cb.equal(normalizedPath, "BOOKED"));
      case "DISPATCHED" ->
          cb.or(
              cb.equal(normalizedPath, normalizedStatus),
              normalizedPath.in("SHIPPED", "FULFILLED"));
      case "SETTLED" ->
          cb.or(cb.equal(normalizedPath, normalizedStatus), cb.equal(normalizedPath, "COMPLETED"));
      default -> cb.equal(normalizedPath, normalizedStatus);
    };
  }

  private String containsPattern(String value) {
    String escaped = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }
}
