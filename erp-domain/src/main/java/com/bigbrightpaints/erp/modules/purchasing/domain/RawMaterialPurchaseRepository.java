package com.bigbrightpaints.erp.modules.purchasing.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;

import jakarta.persistence.LockModeType;

public interface RawMaterialPurchaseRepository extends JpaRepository<RawMaterialPurchase, Long> {
  List<RawMaterialPurchase> findByCompanyOrderByInvoiceDateDesc(Company company);

  @EntityGraph(
      attributePaths = {
        "company",
        "supplier",
        "journalEntry",
        "purchaseOrder",
        "goodsReceipt",
        "lines",
        "lines.rawMaterial",
        "lines.rawMaterialBatch"
      })
  @Query(
      "select p from RawMaterialPurchase p where p.company = :company order by p.invoiceDate desc")
  List<RawMaterialPurchase> findByCompanyWithLinesOrderByInvoiceDateDesc(
      @Param("company") Company company);

  @EntityGraph(
      attributePaths = {
        "company",
        "supplier",
        "journalEntry",
        "purchaseOrder",
        "goodsReceipt",
        "lines",
        "lines.rawMaterial",
        "lines.rawMaterialBatch"
      })
  @Query(
      "select p from RawMaterialPurchase p where p.company = :company and p.supplier = :supplier"
          + " order by p.invoiceDate desc")
  List<RawMaterialPurchase> findByCompanyAndSupplierWithLinesOrderByInvoiceDateDesc(
      @Param("company") Company company, @Param("supplier") Supplier supplier);

  boolean existsByCompanyAndLinesRawMaterial(Company company, RawMaterial rawMaterial);

  @EntityGraph(attributePaths = {"supplier", "lines"})
  List<RawMaterialPurchase> findByCompanyAndInvoiceDateBetweenOrderByInvoiceDateAsc(
      Company company, LocalDate start, LocalDate end);

  @EntityGraph(
      attributePaths = {
        "company",
        "supplier",
        "journalEntry",
        "purchaseOrder",
        "goodsReceipt",
        "lines",
        "lines.rawMaterial",
        "lines.rawMaterialBatch"
      })
  Optional<RawMaterialPurchase> findByCompanyAndId(Company company, Long id);

  Optional<RawMaterialPurchase> findByCompanyAndInvoiceNumberIgnoreCase(
      Company company, String invoiceNumber);

  Optional<RawMaterialPurchase> findByCompanyAndIdempotencyKey(
      Company company, String idempotencyKey);

  @EntityGraph(
      attributePaths = {
        "company",
        "supplier",
        "journalEntry",
        "purchaseOrder",
        "goodsReceipt",
        "lines",
        "lines.rawMaterial",
        "lines.rawMaterialBatch"
      })
  Optional<RawMaterialPurchase> findWithLinesByCompanyAndIdempotencyKey(
      Company company, String idempotencyKey);

  @EntityGraph(attributePaths = {"supplier", "journalEntry", "purchaseOrder", "goodsReceipt"})
  Optional<RawMaterialPurchase> findByCompanyAndGoodsReceipt(
      Company company, GoodsReceipt goodsReceipt);

  @EntityGraph(attributePaths = {"supplier", "journalEntry", "purchaseOrder", "goodsReceipt"})
  List<RawMaterialPurchase> findByCompanyAndGoodsReceipt_IdIn(
      Company company, List<Long> goodsReceiptIds);

  @EntityGraph(attributePaths = {"supplier", "journalEntry", "purchaseOrder", "goodsReceipt"})
  Optional<RawMaterialPurchase> findByCompanyAndJournalEntry(
      Company company, JournalEntry journalEntry);

  @EntityGraph(attributePaths = {"supplier", "journalEntry", "purchaseOrder", "goodsReceipt"})
  List<RawMaterialPurchase> findByCompanyAndJournalEntry_IdIn(
      Company company, List<Long> journalEntryIds);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT p FROM RawMaterialPurchase p WHERE p.company = :company AND p.id = :id")
  Optional<RawMaterialPurchase> lockByCompanyAndId(
      @Param("company") Company company, @Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT p FROM RawMaterialPurchase p WHERE p.company = :company AND LOWER(p.invoiceNumber) ="
          + " LOWER(:invoiceNumber)")
  Optional<RawMaterialPurchase> lockByCompanyAndInvoiceNumberIgnoreCase(
      @Param("company") Company company, @Param("invoiceNumber") String invoiceNumber);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      SELECT p FROM RawMaterialPurchase p
      WHERE p.company = :company
        AND p.supplier = :supplier
        AND p.outstandingAmount > 0
        AND (p.status IS NULL OR p.status NOT IN ('VOID', 'DRAFT', 'REVERSED'))
      ORDER BY CASE WHEN p.dueDate IS NULL THEN 1 ELSE 0 END,
               p.dueDate,
               CASE WHEN p.invoiceDate IS NULL THEN 1 ELSE 0 END,
               p.invoiceDate,
               p.id
      """)
  List<RawMaterialPurchase> lockOpenPurchasesForSettlement(
      @Param("company") Company company, @Param("supplier") Supplier supplier);

  long countByCompanyAndInvoiceDateBetweenAndStatusNot(
      Company company, LocalDate start, LocalDate end, String status);

  long countByCompanyAndInvoiceDateBetweenAndStatusNotIn(
      Company company, LocalDate start, LocalDate end, List<String> statuses);

  long countByCompanyAndInvoiceDateBetweenAndStatusAndJournalEntryIsNull(
      Company company, LocalDate start, LocalDate end, String status);

  long countByCompanyAndInvoiceDateBetweenAndStatusInAndJournalEntryIsNull(
      Company company, LocalDate start, LocalDate end, List<String> statuses);
}
