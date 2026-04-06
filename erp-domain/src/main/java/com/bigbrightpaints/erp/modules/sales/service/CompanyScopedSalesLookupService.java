package com.bigbrightpaints.erp.modules.sales.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CompanyScopedLookupService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Promotion;
import com.bigbrightpaints.erp.modules.sales.domain.PromotionRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesTarget;
import com.bigbrightpaints.erp.modules.sales.domain.SalesTargetRepository;

@Service
public class CompanyScopedSalesLookupService {

  private final CompanyEntityLookup legacyLookup;
  private final CompanyScopedLookupService companyScopedLookupService;
  private final DealerRepository dealerRepository;
  private final SalesOrderRepository salesOrderRepository;
  private final PromotionRepository promotionRepository;
  private final SalesTargetRepository salesTargetRepository;

  @Autowired
  public CompanyScopedSalesLookupService(
      CompanyScopedLookupService companyScopedLookupService,
      DealerRepository dealerRepository,
      SalesOrderRepository salesOrderRepository,
      PromotionRepository promotionRepository,
      SalesTargetRepository salesTargetRepository) {
    this.legacyLookup = null;
    this.companyScopedLookupService = companyScopedLookupService;
    this.dealerRepository = dealerRepository;
    this.salesOrderRepository = salesOrderRepository;
    this.promotionRepository = promotionRepository;
    this.salesTargetRepository = salesTargetRepository;
  }

  private CompanyScopedSalesLookupService(CompanyEntityLookup legacyLookup) {
    this.legacyLookup = legacyLookup;
    this.companyScopedLookupService = null;
    this.dealerRepository = null;
    this.salesOrderRepository = null;
    this.promotionRepository = null;
    this.salesTargetRepository = null;
  }

  public static CompanyScopedSalesLookupService fromLegacy(CompanyEntityLookup legacyLookup) {
    return new CompanyScopedSalesLookupService(legacyLookup);
  }

  public Dealer requireDealer(Company company, Long dealerId) {
    if (legacyLookup != null) {
      return legacyLookup.requireDealer(company, dealerId);
    }
    return companyScopedLookupService.require(
        company, dealerId, dealerRepository::findByCompanyAndId, "Dealer");
  }

  public SalesOrder requireSalesOrder(Company company, Long orderId) {
    if (legacyLookup != null) {
      return legacyLookup.requireSalesOrder(company, orderId);
    }
    return companyScopedLookupService.require(
        company, orderId, salesOrderRepository::findByCompanyAndId, "Sales order");
  }

  public Promotion requirePromotion(Company company, Long promotionId) {
    if (legacyLookup != null) {
      return legacyLookup.requirePromotion(company, promotionId);
    }
    return companyScopedLookupService.require(
        company, promotionId, promotionRepository::findByCompanyAndId, "Promotion");
  }

  public SalesTarget requireSalesTarget(Company company, Long targetId) {
    if (legacyLookup != null) {
      return legacyLookup.requireSalesTarget(company, targetId);
    }
    return companyScopedLookupService.require(
        company, targetId, salesTargetRepository::findByCompanyAndId, "Sales target");
  }
}
