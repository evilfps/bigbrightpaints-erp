package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.test.support.ReflectionFieldAccess;

@ExtendWith(MockitoExtension.class)
class AccountHierarchyServiceTest {

  @Mock private AccountRepository accountRepository;
  @Mock private CompanyContextService companyContextService;

  @Test
  void getChartOfAccountsTree_returnsNestedNodesWithParentReferences() {
    Company company = new Company();
    company.setCode("TREE");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);

    Account assets = account(1L, company, "AST", "Assets", AccountType.ASSET, null);
    Account currentAssets =
        account(2L, company, "AST-CUR", "Current Assets", AccountType.ASSET, assets);
    Account cash = account(3L, company, "CASH", "Cash", AccountType.ASSET, currentAssets);
    Account revenue = account(4L, company, "REV", "Revenue", AccountType.REVENUE, null);
    when(accountRepository.findByCompanyOrderByCodeAsc(company))
        .thenReturn(List.of(assets, currentAssets, cash, revenue));

    AccountHierarchyService service =
        new AccountHierarchyService(accountRepository, companyContextService);
    List<AccountHierarchyService.AccountNode> tree = service.getChartOfAccountsTree();

    assertThat(tree).hasSize(2);
    AccountHierarchyService.AccountNode assetsNode =
        tree.stream().filter(node -> "AST".equals(node.code())).findFirst().orElseThrow();
    assertThat(assetsNode.parentId()).isNull();
    assertThat(assetsNode.children()).hasSize(1);
    AccountHierarchyService.AccountNode currentAssetsNode = assetsNode.children().getFirst();
    assertThat(currentAssetsNode.code()).isEqualTo("AST-CUR");
    assertThat(currentAssetsNode.parentId()).isEqualTo(assetsNode.id());
    assertThat(currentAssetsNode.children()).hasSize(1);
    AccountHierarchyService.AccountNode cashNode = currentAssetsNode.children().getFirst();
    assertThat(cashNode.code()).isEqualTo("CASH");
    assertThat(cashNode.parentId()).isEqualTo(currentAssetsNode.id());
    assertThat(cashNode.children()).isEmpty();
  }

  private Account account(
      Long id, Company company, String code, String name, AccountType type, Account parent) {
    Account account = new Account();
    ReflectionFieldAccess.setField(account, "id", id);
    account.setCompany(company);
    account.setCode(code);
    account.setName(name);
    account.setType(type);
    account.setBalance(BigDecimal.ZERO);
    account.setParent(parent);
    return account;
  }
}
