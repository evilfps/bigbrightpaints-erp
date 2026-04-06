package com.bigbrightpaints.erp.modules.factory.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CompanyScopedLookupService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTask;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTaskRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionPlan;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionPlanRepository;

@Service
public class CompanyScopedFactoryLookupService {

  private final CompanyEntityLookup legacyLookup;
  private final CompanyScopedLookupService companyScopedLookupService;
  private final ProductionLogRepository productionLogRepository;
  private final ProductionPlanRepository productionPlanRepository;
  private final FactoryTaskRepository factoryTaskRepository;

  @Autowired
  public CompanyScopedFactoryLookupService(
      CompanyScopedLookupService companyScopedLookupService,
      ProductionLogRepository productionLogRepository,
      ProductionPlanRepository productionPlanRepository,
      FactoryTaskRepository factoryTaskRepository) {
    this.legacyLookup = null;
    this.companyScopedLookupService = companyScopedLookupService;
    this.productionLogRepository = productionLogRepository;
    this.productionPlanRepository = productionPlanRepository;
    this.factoryTaskRepository = factoryTaskRepository;
  }

  private CompanyScopedFactoryLookupService(CompanyEntityLookup legacyLookup) {
    this.legacyLookup = legacyLookup;
    this.companyScopedLookupService = null;
    this.productionLogRepository = null;
    this.productionPlanRepository = null;
    this.factoryTaskRepository = null;
  }

  public static CompanyScopedFactoryLookupService fromLegacy(CompanyEntityLookup legacyLookup) {
    return new CompanyScopedFactoryLookupService(legacyLookup);
  }

  public ProductionLog requireProductionLog(Company company, Long logId) {
    if (legacyLookup != null) {
      return legacyLookup.requireProductionLog(company, logId);
    }
    return companyScopedLookupService.require(
        company, logId, productionLogRepository::findByCompanyAndId, "Production log");
  }

  public ProductionLog lockProductionLog(Company company, Long logId) {
    if (legacyLookup != null) {
      return legacyLookup.lockProductionLog(company, logId);
    }
    return companyScopedLookupService.require(
        company, logId, productionLogRepository::lockByCompanyAndId, "Production log");
  }

  public ProductionPlan requireProductionPlan(Company company, Long planId) {
    if (legacyLookup != null) {
      return legacyLookup.requireProductionPlan(company, planId);
    }
    return companyScopedLookupService.require(
        company, planId, productionPlanRepository::findByCompanyAndId, "Production plan");
  }

  public FactoryTask requireFactoryTask(Company company, Long taskId) {
    if (legacyLookup != null) {
      return legacyLookup.requireFactoryTask(company, taskId);
    }
    return companyScopedLookupService.require(
        company, taskId, factoryTaskRepository::findByCompanyAndId, "Factory task");
  }
}
