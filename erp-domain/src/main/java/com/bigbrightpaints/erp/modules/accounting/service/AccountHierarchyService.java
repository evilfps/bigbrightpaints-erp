package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Service for hierarchical account operations and consolidated reports.
 * Generates tree structures like:
 *
 * Assets (total: ₹50,00,000)
 * ├── Current Assets (₹30,00,000)
 * │   ├── Cash (₹10,00,000)
 * │   └── Accounts Receivable (₹20,00,000)
 * └── Fixed Assets (₹20,00,000)
 *     ├── Equipment (₹15,00,000)
 *     └── Vehicles (₹5,00,000)
 */
@Service
@Transactional(readOnly = true)
public class AccountHierarchyService {

  private final AccountRepository accountRepository;
  private final CompanyContextService companyContextService;

  public AccountHierarchyService(
      AccountRepository accountRepository, CompanyContextService companyContextService) {
    this.accountRepository = accountRepository;
    this.companyContextService = companyContextService;
  }

  /**
   * Get full chart of accounts as a hierarchical tree
   */
  public List<AccountNode> getChartOfAccountsTree() {
    Company company = companyContextService.requireCurrentCompany();
    List<Account> allAccounts = accountRepository.findByCompanyOrderByCodeAsc(company);
    return buildTree(allAccounts);
  }

  /**
   * Get hierarchical tree for a specific account type (ASSET, LIABILITY, etc.)
   */
  public List<AccountNode> getTreeByType(AccountType type) {
    Company company = companyContextService.requireCurrentCompany();
    List<Account> allAccounts =
        accountRepository.findByCompanyOrderByCodeAsc(company).stream()
            .filter(a -> a.getType() == type)
            .collect(Collectors.toList());
    return buildTree(allAccounts);
  }

  /**
   * Get consolidated balance sheet with hierarchy
   */
  public BalanceSheetHierarchy getBalanceSheetHierarchy() {
    Company company = companyContextService.requireCurrentCompany();
    List<Account> allAccounts = accountRepository.findByCompanyOrderByCodeAsc(company);

    List<AccountNode> assets =
        buildTree(
            allAccounts.stream()
                .filter(a -> a.getType() == AccountType.ASSET)
                .collect(Collectors.toList()));

    List<AccountNode> liabilities =
        buildTree(
            allAccounts.stream()
                .filter(a -> a.getType() == AccountType.LIABILITY)
                .collect(Collectors.toList()));

    List<AccountNode> equity =
        buildTree(
            allAccounts.stream()
                .filter(a -> a.getType() == AccountType.EQUITY)
                .collect(Collectors.toList()));

    BigDecimal totalAssets = sumNodes(assets);
    BigDecimal totalLiabilities = sumNodes(liabilities);
    BigDecimal totalEquity = sumNodes(equity);

    return new BalanceSheetHierarchy(
        assets, totalAssets,
        liabilities, totalLiabilities,
        equity, totalEquity);
  }

  /**
   * Get income statement with hierarchy
   */
  public IncomeStatementHierarchy getIncomeStatementHierarchy() {
    Company company = companyContextService.requireCurrentCompany();
    List<Account> allAccounts = accountRepository.findByCompanyOrderByCodeAsc(company);

    List<AccountNode> revenue =
        buildTree(
            allAccounts.stream()
                .filter(a -> a.getType() == AccountType.REVENUE)
                .collect(Collectors.toList()));

    List<AccountNode> cogs =
        buildTree(
            allAccounts.stream()
                .filter(a -> a.getType() == AccountType.COGS)
                .collect(Collectors.toList()));

    List<AccountNode> expenses =
        buildTree(
            allAccounts.stream()
                .filter(a -> a.getType() == AccountType.EXPENSE)
                .collect(Collectors.toList()));

    BigDecimal totalRevenue = sumNodes(revenue);
    BigDecimal totalCogs = sumNodes(cogs);
    BigDecimal totalExpenses = sumNodes(expenses);
    BigDecimal grossProfit = totalRevenue.subtract(totalCogs);
    BigDecimal netIncome = grossProfit.subtract(totalExpenses);

    return new IncomeStatementHierarchy(
        revenue, totalRevenue, cogs, totalCogs, grossProfit, expenses, totalExpenses, netIncome);
  }

  /**
   * Build tree structure from flat list of accounts
   */
  private List<AccountNode> buildTree(List<Account> accounts) {
    Map<Long, AccountNode> nodeMap = new HashMap<>();
    List<AccountNode> roots = new ArrayList<>();

    // Create nodes for all accounts
    for (Account account : accounts) {
      nodeMap.put(
          account.getId(),
          new AccountNode(
              account.getId(),
              account.getCode(),
              account.getName(),
              account.getType().name(),
              account.getBalance(),
              account.getHierarchyLevel(),
              account.getParent() != null ? account.getParent().getId() : null,
              new ArrayList<>()));
    }

    // Build parent-child relationships
    for (Account account : accounts) {
      AccountNode node = nodeMap.get(account.getId());
      if (account.getParent() != null && nodeMap.containsKey(account.getParent().getId())) {
        AccountNode parentNode = nodeMap.get(account.getParent().getId());
        parentNode.children().add(node);
      } else {
        roots.add(node);
      }
    }

    // Calculate consolidated balances (bottom-up)
    for (AccountNode root : roots) {
      calculateConsolidatedBalance(root);
    }

    return roots;
  }

  /**
   * Recursively calculate consolidated balance for parent accounts
   */
  private BigDecimal calculateConsolidatedBalance(AccountNode node) {
    if (node.children().isEmpty()) {
      return node.balance();
    }

    BigDecimal childrenTotal = BigDecimal.ZERO;
    for (AccountNode child : node.children()) {
      childrenTotal = childrenTotal.add(calculateConsolidatedBalance(child));
    }

    // Update node with consolidated balance (own + children)
    BigDecimal consolidated = node.balance().add(childrenTotal);
    // Note: Since records are immutable, we return the calculated value
    // The actual consolidatedBalance would need a mutable field or wrapper
    return consolidated;
  }

  private BigDecimal sumNodes(List<AccountNode> nodes) {
    BigDecimal total = BigDecimal.ZERO;
    for (AccountNode node : nodes) {
      total = total.add(sumNodeRecursive(node));
    }
    return total;
  }

  private BigDecimal sumNodeRecursive(AccountNode node) {
    BigDecimal sum = node.balance();
    for (AccountNode child : node.children()) {
      sum = sum.add(sumNodeRecursive(child));
    }
    return sum;
  }

  /**
   * Generate ASCII tree representation for display/logging
   */
  public String generateAsciiTree(List<AccountNode> nodes, String currency) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < nodes.size(); i++) {
      boolean isLast = (i == nodes.size() - 1);
      appendNodeToAscii(sb, nodes.get(i), "", isLast, currency);
    }
    return sb.toString();
  }

  private void appendNodeToAscii(
      StringBuilder sb, AccountNode node, String prefix, boolean isLast, String currency) {
    BigDecimal consolidatedBalance = sumNodeRecursive(node);
    String connector = isLast ? "└── " : "├── ";
    sb.append(prefix)
        .append(connector)
        .append(node.name())
        .append(" (")
        .append(currency)
        .append(formatAmount(consolidatedBalance))
        .append(")\n");

    String childPrefix = prefix + (isLast ? "    " : "│   ");
    for (int i = 0; i < node.children().size(); i++) {
      boolean childIsLast = (i == node.children().size() - 1);
      appendNodeToAscii(sb, node.children().get(i), childPrefix, childIsLast, currency);
    }
  }

  private String formatAmount(BigDecimal amount) {
    return String.format("%,.2f", amount);
  }

  // DTOs
  @Schema(name = "AccountNode", description = "Chart of accounts node with recursive children")
  public record AccountNode(
      Long id,
      String code,
      String name,
      String type,
      BigDecimal balance,
      Integer level,
      Long parentId,
      @ArraySchema(schema = @Schema(implementation = AccountNode.class))
          List<AccountNode> children) {}

  public record BalanceSheetHierarchy(
      List<AccountNode> assets,
      BigDecimal totalAssets,
      List<AccountNode> liabilities,
      BigDecimal totalLiabilities,
      List<AccountNode> equity,
      BigDecimal totalEquity) {}

  public record IncomeStatementHierarchy(
      List<AccountNode> revenue,
      BigDecimal totalRevenue,
      List<AccountNode> cogs,
      BigDecimal totalCogs,
      BigDecimal grossProfit,
      List<AccountNode> expenses,
      BigDecimal totalExpenses,
      BigDecimal netIncome) {}
}
