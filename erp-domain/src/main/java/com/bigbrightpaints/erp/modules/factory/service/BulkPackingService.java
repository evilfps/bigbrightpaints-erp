package com.bigbrightpaints.erp.modules.factory.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackResponse;

import jakarta.transaction.Transactional;

@Service
public class BulkPackingService {

  private final CompanyContextService companyContextService;
  private final BulkPackingReadService bulkPackingReadService;

  public BulkPackingService(
      CompanyContextService companyContextService, BulkPackingReadService bulkPackingReadService) {
    this.companyContextService = companyContextService;
    this.bulkPackingReadService = bulkPackingReadService;
  }

  @Transactional
  public List<BulkPackResponse.ChildBatchDto> listBulkBatches(Long finishedGoodId) {
    Company company = companyContextService.requireCurrentCompany();
    return bulkPackingReadService.listBulkBatches(company, finishedGoodId);
  }

  @Transactional
  public List<BulkPackResponse.ChildBatchDto> listChildBatches(Long parentBatchId) {
    Company company = companyContextService.requireCurrentCompany();
    return bulkPackingReadService.listChildBatches(company, parentBatchId);
  }
}
