package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

public class GstReportBreakdownDto {

  @JsonFormat(pattern = "yyyy-MM")
  private YearMonth period;

  @JsonFormat(pattern = "yyyy-MM-dd")
  private LocalDate periodStart;

  @JsonFormat(pattern = "yyyy-MM-dd")
  private LocalDate periodEnd;

  private GstReconciliationDto.GstComponentSummary collected =
      new GstReconciliationDto.GstComponentSummary();
  private GstReconciliationDto.GstComponentSummary inputTaxCredit =
      new GstReconciliationDto.GstComponentSummary();
  private GstReconciliationDto.GstComponentSummary netLiability =
      new GstReconciliationDto.GstComponentSummary();
  private List<GstRateSummary> rateSummaries = List.of();
  private List<GstTransactionDetail> transactionDetails = List.of();

  public YearMonth getPeriod() {
    return period;
  }

  public void setPeriod(YearMonth period) {
    this.period = period;
  }

  public LocalDate getPeriodStart() {
    return periodStart;
  }

  public void setPeriodStart(LocalDate periodStart) {
    this.periodStart = periodStart;
  }

  public LocalDate getPeriodEnd() {
    return periodEnd;
  }

  public void setPeriodEnd(LocalDate periodEnd) {
    this.periodEnd = periodEnd;
  }

  public GstReconciliationDto.GstComponentSummary getCollected() {
    return collected;
  }

  public void setCollected(GstReconciliationDto.GstComponentSummary collected) {
    this.collected = collected;
  }

  public GstReconciliationDto.GstComponentSummary getInputTaxCredit() {
    return inputTaxCredit;
  }

  public void setInputTaxCredit(GstReconciliationDto.GstComponentSummary inputTaxCredit) {
    this.inputTaxCredit = inputTaxCredit;
  }

  public GstReconciliationDto.GstComponentSummary getNetLiability() {
    return netLiability;
  }

  public void setNetLiability(GstReconciliationDto.GstComponentSummary netLiability) {
    this.netLiability = netLiability;
  }

  public List<GstRateSummary> getRateSummaries() {
    return rateSummaries;
  }

  public void setRateSummaries(List<GstRateSummary> rateSummaries) {
    this.rateSummaries = rateSummaries;
  }

  public List<GstTransactionDetail> getTransactionDetails() {
    return transactionDetails;
  }

  public void setTransactionDetails(List<GstTransactionDetail> transactionDetails) {
    this.transactionDetails = transactionDetails;
  }

  public static class GstRateSummary {
    private BigDecimal taxRate = BigDecimal.ZERO;
    private BigDecimal taxableAmount = BigDecimal.ZERO;
    private BigDecimal outputTax = BigDecimal.ZERO;
    private BigDecimal inputTaxCredit = BigDecimal.ZERO;
    private BigDecimal netTax = BigDecimal.ZERO;
    private BigDecimal outputCgst = BigDecimal.ZERO;
    private BigDecimal outputSgst = BigDecimal.ZERO;
    private BigDecimal outputIgst = BigDecimal.ZERO;
    private BigDecimal inputCgst = BigDecimal.ZERO;
    private BigDecimal inputSgst = BigDecimal.ZERO;
    private BigDecimal inputIgst = BigDecimal.ZERO;

    public GstRateSummary() {}

    public GstRateSummary(
        BigDecimal taxRate,
        BigDecimal taxableAmount,
        BigDecimal outputTax,
        BigDecimal inputTaxCredit,
        BigDecimal netTax,
        BigDecimal outputCgst,
        BigDecimal outputSgst,
        BigDecimal outputIgst,
        BigDecimal inputCgst,
        BigDecimal inputSgst,
        BigDecimal inputIgst) {
      this.taxRate = taxRate;
      this.taxableAmount = taxableAmount;
      this.outputTax = outputTax;
      this.inputTaxCredit = inputTaxCredit;
      this.netTax = netTax;
      this.outputCgst = outputCgst;
      this.outputSgst = outputSgst;
      this.outputIgst = outputIgst;
      this.inputCgst = inputCgst;
      this.inputSgst = inputSgst;
      this.inputIgst = inputIgst;
    }

    public BigDecimal getTaxRate() {
      return taxRate;
    }

    public void setTaxRate(BigDecimal taxRate) {
      this.taxRate = taxRate;
    }

    public BigDecimal getTaxableAmount() {
      return taxableAmount;
    }

    public void setTaxableAmount(BigDecimal taxableAmount) {
      this.taxableAmount = taxableAmount;
    }

    public BigDecimal getOutputTax() {
      return outputTax;
    }

    public void setOutputTax(BigDecimal outputTax) {
      this.outputTax = outputTax;
    }

    public BigDecimal getInputTaxCredit() {
      return inputTaxCredit;
    }

    public void setInputTaxCredit(BigDecimal inputTaxCredit) {
      this.inputTaxCredit = inputTaxCredit;
    }

    public BigDecimal getNetTax() {
      return netTax;
    }

    public void setNetTax(BigDecimal netTax) {
      this.netTax = netTax;
    }

    public BigDecimal getOutputCgst() {
      return outputCgst;
    }

    public void setOutputCgst(BigDecimal outputCgst) {
      this.outputCgst = outputCgst;
    }

    public BigDecimal getOutputSgst() {
      return outputSgst;
    }

    public void setOutputSgst(BigDecimal outputSgst) {
      this.outputSgst = outputSgst;
    }

    public BigDecimal getOutputIgst() {
      return outputIgst;
    }

    public void setOutputIgst(BigDecimal outputIgst) {
      this.outputIgst = outputIgst;
    }

    public BigDecimal getInputCgst() {
      return inputCgst;
    }

    public void setInputCgst(BigDecimal inputCgst) {
      this.inputCgst = inputCgst;
    }

    public BigDecimal getInputSgst() {
      return inputSgst;
    }

    public void setInputSgst(BigDecimal inputSgst) {
      this.inputSgst = inputSgst;
    }

    public BigDecimal getInputIgst() {
      return inputIgst;
    }

    public void setInputIgst(BigDecimal inputIgst) {
      this.inputIgst = inputIgst;
    }
  }

  public static class GstTransactionDetail {
    private String sourceType;
    private Long sourceId;
    private String referenceNumber;
    private LocalDate transactionDate;
    private String partyName;
    private BigDecimal taxRate = BigDecimal.ZERO;
    private BigDecimal taxableAmount = BigDecimal.ZERO;
    private BigDecimal cgst = BigDecimal.ZERO;
    private BigDecimal sgst = BigDecimal.ZERO;
    private BigDecimal igst = BigDecimal.ZERO;
    private BigDecimal totalTax = BigDecimal.ZERO;
    private String direction;

    public GstTransactionDetail() {}

    public GstTransactionDetail(
        String sourceType,
        Long sourceId,
        String referenceNumber,
        LocalDate transactionDate,
        String partyName,
        BigDecimal taxRate,
        BigDecimal taxableAmount,
        BigDecimal cgst,
        BigDecimal sgst,
        BigDecimal igst,
        BigDecimal totalTax,
        String direction) {
      this.sourceType = sourceType;
      this.sourceId = sourceId;
      this.referenceNumber = referenceNumber;
      this.transactionDate = transactionDate;
      this.partyName = partyName;
      this.taxRate = taxRate;
      this.taxableAmount = taxableAmount;
      this.cgst = cgst;
      this.sgst = sgst;
      this.igst = igst;
      this.totalTax = totalTax;
      this.direction = direction;
    }

    public String getSourceType() {
      return sourceType;
    }

    public void setSourceType(String sourceType) {
      this.sourceType = sourceType;
    }

    public Long getSourceId() {
      return sourceId;
    }

    public void setSourceId(Long sourceId) {
      this.sourceId = sourceId;
    }

    public String getReferenceNumber() {
      return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
      this.referenceNumber = referenceNumber;
    }

    public LocalDate getTransactionDate() {
      return transactionDate;
    }

    public void setTransactionDate(LocalDate transactionDate) {
      this.transactionDate = transactionDate;
    }

    public String getPartyName() {
      return partyName;
    }

    public void setPartyName(String partyName) {
      this.partyName = partyName;
    }

    public BigDecimal getTaxRate() {
      return taxRate;
    }

    public void setTaxRate(BigDecimal taxRate) {
      this.taxRate = taxRate;
    }

    public BigDecimal getTaxableAmount() {
      return taxableAmount;
    }

    public void setTaxableAmount(BigDecimal taxableAmount) {
      this.taxableAmount = taxableAmount;
    }

    public BigDecimal getCgst() {
      return cgst;
    }

    public void setCgst(BigDecimal cgst) {
      this.cgst = cgst;
    }

    public BigDecimal getSgst() {
      return sgst;
    }

    public void setSgst(BigDecimal sgst) {
      this.sgst = sgst;
    }

    public BigDecimal getIgst() {
      return igst;
    }

    public void setIgst(BigDecimal igst) {
      this.igst = igst;
    }

    public BigDecimal getTotalTax() {
      return totalTax;
    }

    public void setTotalTax(BigDecimal totalTax) {
      this.totalTax = totalTax;
    }

    public String getDirection() {
      return direction;
    }

    public void setDirection(String direction) {
      this.direction = direction;
    }
  }
}
