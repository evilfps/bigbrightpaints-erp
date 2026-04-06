package com.bigbrightpaints.erp.modules.sales.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
    this.companyScopedLookupService = companyScopedLookupService;
    this.dealerRepository = dealerRepository;
    this.salesOrderRepository = salesOrderRepository;
    this.promotionRepository = promotionRepository;
    this.salesTargetRepository = salesTargetRepository;
  }

  public Dealer requireDealer(Company company, Long dealerId) {
    return companyScopedLookupService.require(
        company, dealerId, dealerRepository::findByCompanyAndId, "Dealer");
  }

  public SalesOrder requireSalesOrder(Company company, Long orderId) {
    return companyScopedLookupService.require(
        company, orderId, salesOrderRepository::findByCompanyAndId, "Sales order");
  }

  public Promotion requirePromotion(Company company, Long promotionId) {
    return companyScopedLookupService.require(
        company, promotionId, promotionRepository::findByCompanyAndId, "Promotion");
  }

  public SalesTarget requireSalesTarget(Company company, Long targetId) {
    return companyScopedLookupService.require(
        company, targetId, salesTargetRepository::findByCompanyAndId, "Sales target");
  }
}
