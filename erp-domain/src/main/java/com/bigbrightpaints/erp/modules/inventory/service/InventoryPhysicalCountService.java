package com.bigbrightpaints.erp.modules.inventory.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryPhysicalCount;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryPhysicalCountRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryPhysicalCountTarget;

@Service
public class InventoryPhysicalCountService {

  private final InventoryPhysicalCountRepository inventoryPhysicalCountRepository;
  private final CompanyClock companyClock;

  public InventoryPhysicalCountService(
      InventoryPhysicalCountRepository inventoryPhysicalCountRepository,
      CompanyClock companyClock) {
    this.inventoryPhysicalCountRepository = inventoryPhysicalCountRepository;
    this.companyClock = companyClock;
  }

  @Transactional
  public void recordFinishedGoodCount(
      Company company,
      Long finishedGoodId,
      BigDecimal physicalQuantity,
      LocalDate countDate,
      String sourceReference,
      String note) {
    recordCount(
        company,
        InventoryPhysicalCountTarget.FINISHED_GOOD,
        finishedGoodId,
        physicalQuantity,
        countDate,
        sourceReference,
        note);
  }

  @Transactional
  public void recordRawMaterialCount(
      Company company,
      Long rawMaterialId,
      BigDecimal physicalQuantity,
      LocalDate countDate,
      String sourceReference,
      String note) {
    recordCount(
        company,
        InventoryPhysicalCountTarget.RAW_MATERIAL,
        rawMaterialId,
        physicalQuantity,
        countDate,
        sourceReference,
        note);
  }

  @Transactional(readOnly = true)
  public Map<Long, BigDecimal> latestFinishedGoodCounts(
      Company company, List<Long> finishedGoodIds) {
    return latestCounts(company, InventoryPhysicalCountTarget.FINISHED_GOOD, finishedGoodIds);
  }

  @Transactional(readOnly = true)
  public Map<Long, BigDecimal> latestRawMaterialCounts(Company company, List<Long> rawMaterialIds) {
    return latestCounts(company, InventoryPhysicalCountTarget.RAW_MATERIAL, rawMaterialIds);
  }

  private void recordCount(
      Company company,
      InventoryPhysicalCountTarget target,
      Long inventoryItemId,
      BigDecimal physicalQuantity,
      LocalDate countDate,
      String sourceReference,
      String note) {
    if (company == null || company.getId() == null || inventoryItemId == null) {
      return;
    }
    InventoryPhysicalCount countRecord = new InventoryPhysicalCount();
    countRecord.setCompany(company);
    countRecord.setTarget(target);
    countRecord.setInventoryItemId(inventoryItemId);
    countRecord.setPhysicalQuantity(safeQuantity(physicalQuantity));
    countRecord.setCountDate(countDate != null ? countDate : companyClock.today(company));
    countRecord.setSourceReference(normalizeText(sourceReference));
    countRecord.setNote(normalizeText(note));
    countRecord.setCreatedBy(SecurityActorResolver.resolveActorWithSystemProcessFallback());
    inventoryPhysicalCountRepository.save(countRecord);
  }

  private Map<Long, BigDecimal> latestCounts(
      Company company, InventoryPhysicalCountTarget target, List<Long> inventoryItemIds) {
    if (company == null
        || company.getId() == null
        || inventoryItemIds == null
        || inventoryItemIds.isEmpty()) {
      return Map.of();
    }
    List<Long> canonicalIds =
        inventoryItemIds.stream().filter(Objects::nonNull).distinct().toList();
    if (canonicalIds.isEmpty()) {
      return Map.of();
    }
    List<InventoryPhysicalCount> candidates =
        inventoryPhysicalCountRepository.findLatestCandidates(company, target, canonicalIds);
    Map<Long, BigDecimal> latestByItemId = new HashMap<>();
    for (InventoryPhysicalCount candidate : candidates) {
      if (candidate.getInventoryItemId() == null) {
        continue;
      }
      latestByItemId.putIfAbsent(
          candidate.getInventoryItemId(), safeQuantity(candidate.getPhysicalQuantity()));
    }
    return latestByItemId;
  }

  private BigDecimal safeQuantity(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private String normalizeText(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
