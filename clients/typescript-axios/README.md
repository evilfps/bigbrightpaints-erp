## bbp-erp-api-client@0.1.0

This generator creates TypeScript/JavaScript client that utilizes [axios](https://github.com/axios/axios). The generated Node module can be used in the following environments:

Environment
* Node.js
* Webpack
* Browserify

Language level
* ES5 - you must have a Promises/A+ library installed
* ES6

Module system
* CommonJS
* ES6 module system

It can be used in both TypeScript and JavaScript. In TypeScript, the definition will be automatically resolved via `package.json`. ([Reference](https://www.typescriptlang.org/docs/handbook/declaration-files/consumption.html))

### Building

To build and compile the typescript sources to javascript use:
```
npm install
npm run build
```

### Publishing

First build the package then run `npm publish`

### Consuming

navigate to the folder of your consuming project and run one of the following commands.

_published:_

```
npm install bbp-erp-api-client@0.1.0 --save
```

_unPublished (not recommended):_

```
npm install PATH_TO_GENERATED_PACKAGE --save
```

### Documentation for API Endpoints

All URIs are relative to *http://localhost:50965*

Class | Method | HTTP request | Description
------------ | ------------- | ------------- | -------------
*AccountingCatalogControllerApi* | [**createProduct**](docs/AccountingCatalogControllerApi.md#createproduct) | **POST** /api/v1/accounting/catalog/products | 
*AccountingCatalogControllerApi* | [**importCatalog**](docs/AccountingCatalogControllerApi.md#importcatalog) | **POST** /api/v1/accounting/catalog/import | 
*AccountingCatalogControllerApi* | [**listProducts**](docs/AccountingCatalogControllerApi.md#listproducts) | **GET** /api/v1/accounting/catalog/products | 
*AccountingCatalogControllerApi* | [**updateProduct**](docs/AccountingCatalogControllerApi.md#updateproduct) | **PUT** /api/v1/accounting/catalog/products/{id} | 
*AccountingControllerApi* | [**accounts**](docs/AccountingControllerApi.md#accounts) | **GET** /api/v1/accounting/accounts | 
*AccountingControllerApi* | [**adjustWip**](docs/AccountingControllerApi.md#adjustwip) | **POST** /api/v1/accounting/inventory/wip-adjustment | 
*AccountingControllerApi* | [**auditDigest**](docs/AccountingControllerApi.md#auditdigest) | **GET** /api/v1/accounting/audit/digest | 
*AccountingControllerApi* | [**auditDigestCsv**](docs/AccountingControllerApi.md#auditdigestcsv) | **GET** /api/v1/accounting/audit/digest.csv | 
*AccountingControllerApi* | [**checklist**](docs/AccountingControllerApi.md#checklist) | **GET** /api/v1/accounting/month-end/checklist | 
*AccountingControllerApi* | [**closePeriod**](docs/AccountingControllerApi.md#closeperiod) | **POST** /api/v1/accounting/periods/{periodId}/close | 
*AccountingControllerApi* | [**createAccount**](docs/AccountingControllerApi.md#createaccount) | **POST** /api/v1/accounting/accounts | 
*AccountingControllerApi* | [**createJournalEntry**](docs/AccountingControllerApi.md#createjournalentry) | **POST** /api/v1/accounting/journal-entries | 
*AccountingControllerApi* | [**dealerAging**](docs/AccountingControllerApi.md#dealeraging) | **GET** /api/v1/accounting/aging/dealers/{dealerId} | 
*AccountingControllerApi* | [**dealerAgingPdf**](docs/AccountingControllerApi.md#dealeragingpdf) | **GET** /api/v1/accounting/aging/dealers/{dealerId}/pdf | 
*AccountingControllerApi* | [**dealerStatement**](docs/AccountingControllerApi.md#dealerstatement) | **GET** /api/v1/accounting/statements/dealers/{dealerId} | 
*AccountingControllerApi* | [**dealerStatementPdf**](docs/AccountingControllerApi.md#dealerstatementpdf) | **GET** /api/v1/accounting/statements/dealers/{dealerId}/pdf | 
*AccountingControllerApi* | [**generateGstReturn**](docs/AccountingControllerApi.md#generategstreturn) | **GET** /api/v1/accounting/gst/return | 
*AccountingControllerApi* | [**journalEntries**](docs/AccountingControllerApi.md#journalentries) | **GET** /api/v1/accounting/journal-entries | 
*AccountingControllerApi* | [**listPeriods**](docs/AccountingControllerApi.md#listperiods) | **GET** /api/v1/accounting/periods | 
*AccountingControllerApi* | [**lockPeriod**](docs/AccountingControllerApi.md#lockperiod) | **POST** /api/v1/accounting/periods/{periodId}/lock | 
*AccountingControllerApi* | [**postAccrual**](docs/AccountingControllerApi.md#postaccrual) | **POST** /api/v1/accounting/accruals | 
*AccountingControllerApi* | [**postCreditNote**](docs/AccountingControllerApi.md#postcreditnote) | **POST** /api/v1/accounting/credit-notes | 
*AccountingControllerApi* | [**postDebitNote**](docs/AccountingControllerApi.md#postdebitnote) | **POST** /api/v1/accounting/debit-notes | 
*AccountingControllerApi* | [**reconcileBank**](docs/AccountingControllerApi.md#reconcilebank) | **POST** /api/v1/accounting/bank-reconciliation | 
*AccountingControllerApi* | [**recordDealerReceipt**](docs/AccountingControllerApi.md#recorddealerreceipt) | **POST** /api/v1/accounting/receipts/dealer | 
*AccountingControllerApi* | [**recordInventoryCount**](docs/AccountingControllerApi.md#recordinventorycount) | **POST** /api/v1/accounting/inventory/physical-count | 
*AccountingControllerApi* | [**recordLandedCost**](docs/AccountingControllerApi.md#recordlandedcost) | **POST** /api/v1/accounting/inventory/landed-cost | 
*AccountingControllerApi* | [**recordPayrollPayment**](docs/AccountingControllerApi.md#recordpayrollpayment) | **POST** /api/v1/accounting/payroll/payments | 
*AccountingControllerApi* | [**recordSalesReturn**](docs/AccountingControllerApi.md#recordsalesreturn) | **POST** /api/v1/accounting/sales/returns | 
*AccountingControllerApi* | [**recordSupplierPayment**](docs/AccountingControllerApi.md#recordsupplierpayment) | **POST** /api/v1/accounting/suppliers/payments | 
*AccountingControllerApi* | [**reopenPeriod**](docs/AccountingControllerApi.md#reopenperiod) | **POST** /api/v1/accounting/periods/{periodId}/reopen | 
*AccountingControllerApi* | [**revalueInventory**](docs/AccountingControllerApi.md#revalueinventory) | **POST** /api/v1/accounting/inventory/revaluation | 
*AccountingControllerApi* | [**reverseJournalEntry**](docs/AccountingControllerApi.md#reversejournalentry) | **POST** /api/v1/accounting/journal-entries/{entryId}/reverse | 
*AccountingControllerApi* | [**settleDealer**](docs/AccountingControllerApi.md#settledealer) | **POST** /api/v1/accounting/settlements/dealers | 
*AccountingControllerApi* | [**settleSupplier**](docs/AccountingControllerApi.md#settlesupplier) | **POST** /api/v1/accounting/settlements/suppliers | 
*AccountingControllerApi* | [**supplierAging**](docs/AccountingControllerApi.md#supplieraging) | **GET** /api/v1/accounting/aging/suppliers/{supplierId} | 
*AccountingControllerApi* | [**supplierAgingPdf**](docs/AccountingControllerApi.md#supplieragingpdf) | **GET** /api/v1/accounting/aging/suppliers/{supplierId}/pdf | 
*AccountingControllerApi* | [**supplierStatement**](docs/AccountingControllerApi.md#supplierstatement) | **GET** /api/v1/accounting/statements/suppliers/{supplierId} | 
*AccountingControllerApi* | [**supplierStatementPdf**](docs/AccountingControllerApi.md#supplierstatementpdf) | **GET** /api/v1/accounting/statements/suppliers/{supplierId}/pdf | 
*AccountingControllerApi* | [**updateChecklist**](docs/AccountingControllerApi.md#updatechecklist) | **POST** /api/v1/accounting/month-end/checklist/{periodId} | 
*AccountingControllerApi* | [**writeOffBadDebt**](docs/AccountingControllerApi.md#writeoffbaddebt) | **POST** /api/v1/accounting/bad-debts/write-off | 
*AdminUserControllerApi* | [**create2**](docs/AdminUserControllerApi.md#create2) | **POST** /api/v1/admin/users | 
*AdminUserControllerApi* | [**list2**](docs/AdminUserControllerApi.md#list2) | **GET** /api/v1/admin/users | 
*AdminUserControllerApi* | [**suspend**](docs/AdminUserControllerApi.md#suspend) | **PATCH** /api/v1/admin/users/{id}/suspend | 
*AdminUserControllerApi* | [**unsuspend**](docs/AdminUserControllerApi.md#unsuspend) | **PATCH** /api/v1/admin/users/{id}/unsuspend | 
*AdminUserControllerApi* | [**update2**](docs/AdminUserControllerApi.md#update2) | **PUT** /api/v1/admin/users/{id} | 
*AuthControllerApi* | [**changePassword**](docs/AuthControllerApi.md#changepassword) | **POST** /api/v1/auth/password/change | 
*AuthControllerApi* | [**forgotPassword**](docs/AuthControllerApi.md#forgotpassword) | **POST** /api/v1/auth/password/forgot | 
*AuthControllerApi* | [**login**](docs/AuthControllerApi.md#login) | **POST** /api/v1/auth/login | 
*AuthControllerApi* | [**logout**](docs/AuthControllerApi.md#logout) | **POST** /api/v1/auth/logout | 
*AuthControllerApi* | [**me**](docs/AuthControllerApi.md#me) | **GET** /api/v1/auth/me | 
*AuthControllerApi* | [**refresh**](docs/AuthControllerApi.md#refresh) | **POST** /api/v1/auth/refresh-token | 
*AuthControllerApi* | [**resetPassword**](docs/AuthControllerApi.md#resetpassword) | **POST** /api/v1/auth/password/reset | 
*CompanyControllerApi* | [**_delete**](docs/CompanyControllerApi.md#_delete) | **DELETE** /api/v1/companies/{id} | 
*CompanyControllerApi* | [**create1**](docs/CompanyControllerApi.md#create1) | **POST** /api/v1/companies | 
*CompanyControllerApi* | [**list1**](docs/CompanyControllerApi.md#list1) | **GET** /api/v1/companies | 
*CompanyControllerApi* | [**update**](docs/CompanyControllerApi.md#update) | **PUT** /api/v1/companies/{id} | 
*DashboardControllerApi* | [**adminDashboard**](docs/DashboardControllerApi.md#admindashboard) | **GET** /api/v1/orchestrator/dashboard/admin | 
*DashboardControllerApi* | [**factoryDashboard**](docs/DashboardControllerApi.md#factorydashboard) | **GET** /api/v1/orchestrator/dashboard/factory | 
*DashboardControllerApi* | [**financeDashboard**](docs/DashboardControllerApi.md#financedashboard) | **GET** /api/v1/orchestrator/dashboard/finance | 
*DealerControllerApi* | [**createDealer**](docs/DealerControllerApi.md#createdealer) | **POST** /api/v1/dealers | 
*DealerControllerApi* | [**dealerLedger**](docs/DealerControllerApi.md#dealerledger) | **GET** /api/v1/dealers/{dealerId}/ledger | 
*DealerControllerApi* | [**holdIfOverdue**](docs/DealerControllerApi.md#holdifoverdue) | **POST** /api/v1/dealers/{dealerId}/dunning/hold | 
*DealerControllerApi* | [**listDealers**](docs/DealerControllerApi.md#listdealers) | **GET** /api/v1/dealers | 
*DealerControllerApi* | [**searchDealers**](docs/DealerControllerApi.md#searchdealers) | **GET** /api/v1/dealers/search | 
*DemoControllerApi* | [**ping**](docs/DemoControllerApi.md#ping) | **GET** /api/v1/demo/ping | 
*FactoryControllerApi* | [**allocateCosts**](docs/FactoryControllerApi.md#allocatecosts) | **POST** /api/v1/factory/cost-allocation | 
*FactoryControllerApi* | [**batches1**](docs/FactoryControllerApi.md#batches1) | **GET** /api/v1/factory/production-batches | 
*FactoryControllerApi* | [**createPlan**](docs/FactoryControllerApi.md#createplan) | **POST** /api/v1/factory/production-plans | 
*FactoryControllerApi* | [**createTask**](docs/FactoryControllerApi.md#createtask) | **POST** /api/v1/factory/tasks | 
*FactoryControllerApi* | [**dashboard1**](docs/FactoryControllerApi.md#dashboard1) | **GET** /api/v1/factory/dashboard | 
*FactoryControllerApi* | [**deletePlan**](docs/FactoryControllerApi.md#deleteplan) | **DELETE** /api/v1/factory/production-plans/{id} | 
*FactoryControllerApi* | [**logBatch**](docs/FactoryControllerApi.md#logbatch) | **POST** /api/v1/factory/production-batches | 
*FactoryControllerApi* | [**plans**](docs/FactoryControllerApi.md#plans) | **GET** /api/v1/factory/production-plans | 
*FactoryControllerApi* | [**tasks**](docs/FactoryControllerApi.md#tasks) | **GET** /api/v1/factory/tasks | 
*FactoryControllerApi* | [**updatePlan**](docs/FactoryControllerApi.md#updateplan) | **PUT** /api/v1/factory/production-plans/{id} | 
*FactoryControllerApi* | [**updatePlanStatus**](docs/FactoryControllerApi.md#updateplanstatus) | **PATCH** /api/v1/factory/production-plans/{id}/status | 
*FactoryControllerApi* | [**updateTask**](docs/FactoryControllerApi.md#updatetask) | **PUT** /api/v1/factory/tasks/{id} | 
*HrControllerApi* | [**createEmployee**](docs/HrControllerApi.md#createemployee) | **POST** /api/v1/hr/employees | 
*HrControllerApi* | [**createLeaveRequest**](docs/HrControllerApi.md#createleaverequest) | **POST** /api/v1/hr/leave-requests | 
*HrControllerApi* | [**createPayrollRun**](docs/HrControllerApi.md#createpayrollrun) | **POST** /api/v1/hr/payroll-runs | 
*HrControllerApi* | [**deleteEmployee**](docs/HrControllerApi.md#deleteemployee) | **DELETE** /api/v1/hr/employees/{id} | 
*HrControllerApi* | [**employees**](docs/HrControllerApi.md#employees) | **GET** /api/v1/hr/employees | 
*HrControllerApi* | [**leaveRequests**](docs/HrControllerApi.md#leaverequests) | **GET** /api/v1/hr/leave-requests | 
*HrControllerApi* | [**payrollRuns**](docs/HrControllerApi.md#payrollruns) | **GET** /api/v1/hr/payroll-runs | 
*HrControllerApi* | [**updateEmployee**](docs/HrControllerApi.md#updateemployee) | **PUT** /api/v1/hr/employees/{id} | 
*HrControllerApi* | [**updateLeaveStatus**](docs/HrControllerApi.md#updateleavestatus) | **PATCH** /api/v1/hr/leave-requests/{id}/status | 
*InventoryAdjustmentControllerApi* | [**createAdjustment**](docs/InventoryAdjustmentControllerApi.md#createadjustment) | **POST** /api/v1/inventory/adjustments | 
*InventoryAdjustmentControllerApi* | [**listAdjustments**](docs/InventoryAdjustmentControllerApi.md#listadjustments) | **GET** /api/v1/inventory/adjustments | 
*InvoiceControllerApi* | [**dealerInvoices**](docs/InvoiceControllerApi.md#dealerinvoices) | **GET** /api/v1/invoices/dealers/{dealerId} | 
*InvoiceControllerApi* | [**getInvoice**](docs/InvoiceControllerApi.md#getinvoice) | **GET** /api/v1/invoices/{id} | 
*InvoiceControllerApi* | [**listInvoices**](docs/InvoiceControllerApi.md#listinvoices) | **GET** /api/v1/invoices | 
*MfaControllerApi* | [**activate**](docs/MfaControllerApi.md#activate) | **POST** /api/v1/auth/mfa/activate | 
*MfaControllerApi* | [**disable**](docs/MfaControllerApi.md#disable) | **POST** /api/v1/auth/mfa/disable | 
*MfaControllerApi* | [**setup**](docs/MfaControllerApi.md#setup) | **POST** /api/v1/auth/mfa/setup | 
*MultiCompanyControllerApi* | [**switchCompany**](docs/MultiCompanyControllerApi.md#switchcompany) | **POST** /api/v1/multi-company/companies/switch | 
*OrchestratorControllerApi* | [**approveOrder**](docs/OrchestratorControllerApi.md#approveorder) | **POST** /api/v1/orchestrator/orders/{orderId}/approve | 
*OrchestratorControllerApi* | [**dispatch**](docs/OrchestratorControllerApi.md#dispatch) | **POST** /api/v1/orchestrator/factory/dispatch/{batchId} | 
*OrchestratorControllerApi* | [**dispatchOrder**](docs/OrchestratorControllerApi.md#dispatchorder) | **POST** /api/v1/orchestrator/dispatch | 
*OrchestratorControllerApi* | [**eventHealth**](docs/OrchestratorControllerApi.md#eventhealth) | **GET** /api/v1/orchestrator/health/events | 
*OrchestratorControllerApi* | [**fulfillOrder**](docs/OrchestratorControllerApi.md#fulfillorder) | **POST** /api/v1/orchestrator/orders/{orderId}/fulfillment | 
*OrchestratorControllerApi* | [**integrationsHealth**](docs/OrchestratorControllerApi.md#integrationshealth) | **GET** /api/v1/orchestrator/health/integrations | 
*OrchestratorControllerApi* | [**runPayroll**](docs/OrchestratorControllerApi.md#runpayroll) | **POST** /api/v1/orchestrator/payroll/run | 
*OrchestratorControllerApi* | [**trace**](docs/OrchestratorControllerApi.md#trace) | **GET** /api/v1/orchestrator/traces/{traceId} | 
*PackingControllerApi* | [**completePacking**](docs/PackingControllerApi.md#completepacking) | **POST** /api/v1/factory/packing-records/{productionLogId}/complete | 
*PackingControllerApi* | [**listUnpackedBatches**](docs/PackingControllerApi.md#listunpackedbatches) | **GET** /api/v1/factory/unpacked-batches | 
*PackingControllerApi* | [**packingHistory**](docs/PackingControllerApi.md#packinghistory) | **GET** /api/v1/factory/production-logs/{productionLogId}/packing-history | 
*PackingControllerApi* | [**recordPacking**](docs/PackingControllerApi.md#recordpacking) | **POST** /api/v1/factory/packing-records | 
*PortalInsightsControllerApi* | [**dashboard**](docs/PortalInsightsControllerApi.md#dashboard) | **GET** /api/v1/portal/dashboard | 
*PortalInsightsControllerApi* | [**operations**](docs/PortalInsightsControllerApi.md#operations) | **GET** /api/v1/portal/operations | 
*PortalInsightsControllerApi* | [**workforce**](docs/PortalInsightsControllerApi.md#workforce) | **GET** /api/v1/portal/workforce | 
*ProductionCatalogControllerApi* | [**listBrandProducts**](docs/ProductionCatalogControllerApi.md#listbrandproducts) | **GET** /api/v1/production/brands/{brandId}/products | 
*ProductionCatalogControllerApi* | [**listBrands**](docs/ProductionCatalogControllerApi.md#listbrands) | **GET** /api/v1/production/brands | 
*ProductionLogControllerApi* | [**create**](docs/ProductionLogControllerApi.md#create) | **POST** /api/v1/factory/production/logs | 
*ProductionLogControllerApi* | [**detail**](docs/ProductionLogControllerApi.md#detail) | **GET** /api/v1/factory/production/logs/{id} | 
*ProductionLogControllerApi* | [**list**](docs/ProductionLogControllerApi.md#list) | **GET** /api/v1/factory/production/logs | 
*RawMaterialControllerApi* | [**batches**](docs/RawMaterialControllerApi.md#batches) | **GET** /api/v1/raw-material-batches/{rawMaterialId} | 
*RawMaterialControllerApi* | [**createBatch**](docs/RawMaterialControllerApi.md#createbatch) | **POST** /api/v1/raw-material-batches/{rawMaterialId} | 
*RawMaterialControllerApi* | [**createRawMaterial**](docs/RawMaterialControllerApi.md#createrawmaterial) | **POST** /api/v1/accounting/raw-materials | 
*RawMaterialControllerApi* | [**deleteRawMaterial**](docs/RawMaterialControllerApi.md#deleterawmaterial) | **DELETE** /api/v1/accounting/raw-materials/{id} | 
*RawMaterialControllerApi* | [**intake**](docs/RawMaterialControllerApi.md#intake) | **POST** /api/v1/raw-materials/intake | 
*RawMaterialControllerApi* | [**inventory**](docs/RawMaterialControllerApi.md#inventory) | **GET** /api/v1/raw-materials/stock/inventory | 
*RawMaterialControllerApi* | [**listRawMaterials**](docs/RawMaterialControllerApi.md#listrawmaterials) | **GET** /api/v1/accounting/raw-materials | 
*RawMaterialControllerApi* | [**lowStock**](docs/RawMaterialControllerApi.md#lowstock) | **GET** /api/v1/raw-materials/stock/low-stock | 
*RawMaterialControllerApi* | [**stockSummary**](docs/RawMaterialControllerApi.md#stocksummary) | **GET** /api/v1/raw-materials/stock | 
*RawMaterialControllerApi* | [**updateRawMaterial**](docs/RawMaterialControllerApi.md#updaterawmaterial) | **PUT** /api/v1/accounting/raw-materials/{id} | 
*RawMaterialPurchaseControllerApi* | [**createPurchase**](docs/RawMaterialPurchaseControllerApi.md#createpurchase) | **POST** /api/v1/purchasing/raw-material-purchases | 
*RawMaterialPurchaseControllerApi* | [**getPurchase**](docs/RawMaterialPurchaseControllerApi.md#getpurchase) | **GET** /api/v1/purchasing/raw-material-purchases/{id} | 
*RawMaterialPurchaseControllerApi* | [**listPurchases**](docs/RawMaterialPurchaseControllerApi.md#listpurchases) | **GET** /api/v1/purchasing/raw-material-purchases | 
*RawMaterialPurchaseControllerApi* | [**recordPurchaseReturn**](docs/RawMaterialPurchaseControllerApi.md#recordpurchasereturn) | **POST** /api/v1/purchasing/raw-material-purchases/returns | 
*ReportControllerApi* | [**accountStatement**](docs/ReportControllerApi.md#accountstatement) | **GET** /api/v1/reports/account-statement | 
*ReportControllerApi* | [**agedDebtors**](docs/ReportControllerApi.md#ageddebtors) | **GET** /api/v1/accounting/reports/aged-debtors | 
*ReportControllerApi* | [**balanceSheet**](docs/ReportControllerApi.md#balancesheet) | **GET** /api/v1/reports/balance-sheet | 
*ReportControllerApi* | [**balanceWarnings**](docs/ReportControllerApi.md#balancewarnings) | **GET** /api/v1/reports/balance-warnings | 
*ReportControllerApi* | [**cashFlow**](docs/ReportControllerApi.md#cashflow) | **GET** /api/v1/reports/cash-flow | 
*ReportControllerApi* | [**costBreakdown**](docs/ReportControllerApi.md#costbreakdown) | **GET** /api/v1/reports/production-logs/{id}/cost-breakdown | 
*ReportControllerApi* | [**inventoryReconciliation**](docs/ReportControllerApi.md#inventoryreconciliation) | **GET** /api/v1/reports/inventory-reconciliation | 
*ReportControllerApi* | [**inventoryValuation**](docs/ReportControllerApi.md#inventoryvaluation) | **GET** /api/v1/reports/inventory-valuation | 
*ReportControllerApi* | [**monthlyProductionCosts**](docs/ReportControllerApi.md#monthlyproductioncosts) | **GET** /api/v1/reports/monthly-production-costs | 
*ReportControllerApi* | [**profitLoss**](docs/ReportControllerApi.md#profitloss) | **GET** /api/v1/reports/profit-loss | 
*ReportControllerApi* | [**reconciliationDashboard**](docs/ReportControllerApi.md#reconciliationdashboard) | **GET** /api/v1/reports/reconciliation-dashboard | 
*ReportControllerApi* | [**trialBalance**](docs/ReportControllerApi.md#trialbalance) | **GET** /api/v1/reports/trial-balance | 
*ReportControllerApi* | [**wastageReport**](docs/ReportControllerApi.md#wastagereport) | **GET** /api/v1/reports/wastage | 
*RoleControllerApi* | [**createRole**](docs/RoleControllerApi.md#createrole) | **POST** /api/v1/admin/roles | 
*RoleControllerApi* | [**listRoles**](docs/RoleControllerApi.md#listroles) | **GET** /api/v1/admin/roles | 
*SalesControllerApi* | [**cancelOrder**](docs/SalesControllerApi.md#cancelorder) | **POST** /api/v1/sales/orders/{id}/cancel | 
*SalesControllerApi* | [**confirmDispatch**](docs/SalesControllerApi.md#confirmdispatch) | **POST** /api/v1/sales/dispatch/confirm | 
*SalesControllerApi* | [**confirmOrder**](docs/SalesControllerApi.md#confirmorder) | **POST** /api/v1/sales/orders/{id}/confirm | 
*SalesControllerApi* | [**createCreditRequest**](docs/SalesControllerApi.md#createcreditrequest) | **POST** /api/v1/sales/credit-requests | 
*SalesControllerApi* | [**createOrder**](docs/SalesControllerApi.md#createorder) | **POST** /api/v1/sales/orders | 
*SalesControllerApi* | [**createPromotion**](docs/SalesControllerApi.md#createpromotion) | **POST** /api/v1/sales/promotions | 
*SalesControllerApi* | [**createTarget**](docs/SalesControllerApi.md#createtarget) | **POST** /api/v1/sales/targets | 
*SalesControllerApi* | [**creditRequests**](docs/SalesControllerApi.md#creditrequests) | **GET** /api/v1/sales/credit-requests | 
*SalesControllerApi* | [**deleteOrder**](docs/SalesControllerApi.md#deleteorder) | **DELETE** /api/v1/sales/orders/{id} | 
*SalesControllerApi* | [**deletePromotion**](docs/SalesControllerApi.md#deletepromotion) | **DELETE** /api/v1/sales/promotions/{id} | 
*SalesControllerApi* | [**deleteTarget**](docs/SalesControllerApi.md#deletetarget) | **DELETE** /api/v1/sales/targets/{id} | 
*SalesControllerApi* | [**orders**](docs/SalesControllerApi.md#orders) | **GET** /api/v1/sales/orders | 
*SalesControllerApi* | [**promotions**](docs/SalesControllerApi.md#promotions) | **GET** /api/v1/sales/promotions | 
*SalesControllerApi* | [**targets**](docs/SalesControllerApi.md#targets) | **GET** /api/v1/sales/targets | 
*SalesControllerApi* | [**updateCreditRequest**](docs/SalesControllerApi.md#updatecreditrequest) | **PUT** /api/v1/sales/credit-requests/{id} | 
*SalesControllerApi* | [**updateOrder**](docs/SalesControllerApi.md#updateorder) | **PUT** /api/v1/sales/orders/{id} | 
*SalesControllerApi* | [**updatePromotion**](docs/SalesControllerApi.md#updatepromotion) | **PUT** /api/v1/sales/promotions/{id} | 
*SalesControllerApi* | [**updateStatus**](docs/SalesControllerApi.md#updatestatus) | **PATCH** /api/v1/sales/orders/{id}/status | 
*SalesControllerApi* | [**updateTarget**](docs/SalesControllerApi.md#updatetarget) | **PUT** /api/v1/sales/targets/{id} | 
*SupplierControllerApi* | [**createSupplier**](docs/SupplierControllerApi.md#createsupplier) | **POST** /api/v1/suppliers | 
*SupplierControllerApi* | [**getSupplier**](docs/SupplierControllerApi.md#getsupplier) | **GET** /api/v1/suppliers/{id} | 
*SupplierControllerApi* | [**listSuppliers**](docs/SupplierControllerApi.md#listsuppliers) | **GET** /api/v1/suppliers | 
*SupplierControllerApi* | [**updateSupplier**](docs/SupplierControllerApi.md#updatesupplier) | **PUT** /api/v1/suppliers/{id} | 
*UserProfileControllerApi* | [**profile**](docs/UserProfileControllerApi.md#profile) | **GET** /api/v1/auth/profile | 
*UserProfileControllerApi* | [**update1**](docs/UserProfileControllerApi.md#update1) | **PUT** /api/v1/auth/profile | 


### Documentation For Models

 - [AccountDto](docs/AccountDto.md)
 - [AccountRequest](docs/AccountRequest.md)
 - [AccountStatementEntryDto](docs/AccountStatementEntryDto.md)
 - [AccountingPeriodCloseRequest](docs/AccountingPeriodCloseRequest.md)
 - [AccountingPeriodDto](docs/AccountingPeriodDto.md)
 - [AccountingPeriodLockRequest](docs/AccountingPeriodLockRequest.md)
 - [AccountingPeriodReopenRequest](docs/AccountingPeriodReopenRequest.md)
 - [AccrualRequest](docs/AccrualRequest.md)
 - [AgedDebtorDto](docs/AgedDebtorDto.md)
 - [AgingBucketDto](docs/AgingBucketDto.md)
 - [AgingSummaryResponse](docs/AgingSummaryResponse.md)
 - [Allocation](docs/Allocation.md)
 - [ApiResponseAccountDto](docs/ApiResponseAccountDto.md)
 - [ApiResponseAccountingPeriodDto](docs/ApiResponseAccountingPeriodDto.md)
 - [ApiResponseAgingSummaryResponse](docs/ApiResponseAgingSummaryResponse.md)
 - [ApiResponseAuditDigestResponse](docs/ApiResponseAuditDigestResponse.md)
 - [ApiResponseBalanceSheetDto](docs/ApiResponseBalanceSheetDto.md)
 - [ApiResponseBankReconciliationSummaryDto](docs/ApiResponseBankReconciliationSummaryDto.md)
 - [ApiResponseCashFlowDto](docs/ApiResponseCashFlowDto.md)
 - [ApiResponseCatalogImportResponse](docs/ApiResponseCatalogImportResponse.md)
 - [ApiResponseCompanyDto](docs/ApiResponseCompanyDto.md)
 - [ApiResponseCostAllocationResponse](docs/ApiResponseCostAllocationResponse.md)
 - [ApiResponseCostBreakdownDto](docs/ApiResponseCostBreakdownDto.md)
 - [ApiResponseCreditRequestDto](docs/ApiResponseCreditRequestDto.md)
 - [ApiResponseDashboardInsights](docs/ApiResponseDashboardInsights.md)
 - [ApiResponseDealerResponse](docs/ApiResponseDealerResponse.md)
 - [ApiResponseDispatchConfirmResponse](docs/ApiResponseDispatchConfirmResponse.md)
 - [ApiResponseEmployeeDto](docs/ApiResponseEmployeeDto.md)
 - [ApiResponseFactoryDashboardDto](docs/ApiResponseFactoryDashboardDto.md)
 - [ApiResponseFactoryTaskDto](docs/ApiResponseFactoryTaskDto.md)
 - [ApiResponseGstReturnDto](docs/ApiResponseGstReturnDto.md)
 - [ApiResponseInventoryAdjustmentDto](docs/ApiResponseInventoryAdjustmentDto.md)
 - [ApiResponseInventoryCountResponse](docs/ApiResponseInventoryCountResponse.md)
 - [ApiResponseInventoryValuationDto](docs/ApiResponseInventoryValuationDto.md)
 - [ApiResponseInvoiceDto](docs/ApiResponseInvoiceDto.md)
 - [ApiResponseJournalEntryDto](docs/ApiResponseJournalEntryDto.md)
 - [ApiResponseLeaveRequestDto](docs/ApiResponseLeaveRequestDto.md)
 - [ApiResponseListAccountDto](docs/ApiResponseListAccountDto.md)
 - [ApiResponseListAccountStatementEntryDto](docs/ApiResponseListAccountStatementEntryDto.md)
 - [ApiResponseListAccountingPeriodDto](docs/ApiResponseListAccountingPeriodDto.md)
 - [ApiResponseListAgedDebtorDto](docs/ApiResponseListAgedDebtorDto.md)
 - [ApiResponseListBalanceWarningDto](docs/ApiResponseListBalanceWarningDto.md)
 - [ApiResponseListCompanyDto](docs/ApiResponseListCompanyDto.md)
 - [ApiResponseListCreditRequestDto](docs/ApiResponseListCreditRequestDto.md)
 - [ApiResponseListDealerLookupResponse](docs/ApiResponseListDealerLookupResponse.md)
 - [ApiResponseListDealerResponse](docs/ApiResponseListDealerResponse.md)
 - [ApiResponseListEmployeeDto](docs/ApiResponseListEmployeeDto.md)
 - [ApiResponseListFactoryTaskDto](docs/ApiResponseListFactoryTaskDto.md)
 - [ApiResponseListInventoryAdjustmentDto](docs/ApiResponseListInventoryAdjustmentDto.md)
 - [ApiResponseListInventoryStockSnapshot](docs/ApiResponseListInventoryStockSnapshot.md)
 - [ApiResponseListInvoiceDto](docs/ApiResponseListInvoiceDto.md)
 - [ApiResponseListJournalEntryDto](docs/ApiResponseListJournalEntryDto.md)
 - [ApiResponseListLeaveRequestDto](docs/ApiResponseListLeaveRequestDto.md)
 - [ApiResponseListPackingRecordDto](docs/ApiResponseListPackingRecordDto.md)
 - [ApiResponseListPayrollRunDto](docs/ApiResponseListPayrollRunDto.md)
 - [ApiResponseListProductionBatchDto](docs/ApiResponseListProductionBatchDto.md)
 - [ApiResponseListProductionBrandDto](docs/ApiResponseListProductionBrandDto.md)
 - [ApiResponseListProductionLogDto](docs/ApiResponseListProductionLogDto.md)
 - [ApiResponseListProductionPlanDto](docs/ApiResponseListProductionPlanDto.md)
 - [ApiResponseListProductionProductDto](docs/ApiResponseListProductionProductDto.md)
 - [ApiResponseListPromotionDto](docs/ApiResponseListPromotionDto.md)
 - [ApiResponseListRawMaterialBatchDto](docs/ApiResponseListRawMaterialBatchDto.md)
 - [ApiResponseListRawMaterialDto](docs/ApiResponseListRawMaterialDto.md)
 - [ApiResponseListRawMaterialPurchaseResponse](docs/ApiResponseListRawMaterialPurchaseResponse.md)
 - [ApiResponseListRoleDto](docs/ApiResponseListRoleDto.md)
 - [ApiResponseListSalesOrderDto](docs/ApiResponseListSalesOrderDto.md)
 - [ApiResponseListSalesTargetDto](docs/ApiResponseListSalesTargetDto.md)
 - [ApiResponseListSupplierResponse](docs/ApiResponseListSupplierResponse.md)
 - [ApiResponseListUnpackedBatchDto](docs/ApiResponseListUnpackedBatchDto.md)
 - [ApiResponseListUserDto](docs/ApiResponseListUserDto.md)
 - [ApiResponseListWastageReportDto](docs/ApiResponseListWastageReportDto.md)
 - [ApiResponseMapStringObject](docs/ApiResponseMapStringObject.md)
 - [ApiResponseMeResponse](docs/ApiResponseMeResponse.md)
 - [ApiResponseMfaSetupResponse](docs/ApiResponseMfaSetupResponse.md)
 - [ApiResponseMfaStatusResponse](docs/ApiResponseMfaStatusResponse.md)
 - [ApiResponseMonthEndChecklistDto](docs/ApiResponseMonthEndChecklistDto.md)
 - [ApiResponseMonthlyProductionCostDto](docs/ApiResponseMonthlyProductionCostDto.md)
 - [ApiResponseOperationsInsights](docs/ApiResponseOperationsInsights.md)
 - [ApiResponsePartnerSettlementResponse](docs/ApiResponsePartnerSettlementResponse.md)
 - [ApiResponsePartnerStatementResponse](docs/ApiResponsePartnerStatementResponse.md)
 - [ApiResponsePayrollRunDto](docs/ApiResponsePayrollRunDto.md)
 - [ApiResponseProductionBatchDto](docs/ApiResponseProductionBatchDto.md)
 - [ApiResponseProductionLogDetailDto](docs/ApiResponseProductionLogDetailDto.md)
 - [ApiResponseProductionPlanDto](docs/ApiResponseProductionPlanDto.md)
 - [ApiResponseProductionProductDto](docs/ApiResponseProductionProductDto.md)
 - [ApiResponseProfileResponse](docs/ApiResponseProfileResponse.md)
 - [ApiResponseProfitLossDto](docs/ApiResponseProfitLossDto.md)
 - [ApiResponsePromotionDto](docs/ApiResponsePromotionDto.md)
 - [ApiResponseRawMaterialBatchDto](docs/ApiResponseRawMaterialBatchDto.md)
 - [ApiResponseRawMaterialDto](docs/ApiResponseRawMaterialDto.md)
 - [ApiResponseRawMaterialPurchaseResponse](docs/ApiResponseRawMaterialPurchaseResponse.md)
 - [ApiResponseReconciliationDashboardDto](docs/ApiResponseReconciliationDashboardDto.md)
 - [ApiResponseReconciliationSummaryDto](docs/ApiResponseReconciliationSummaryDto.md)
 - [ApiResponseRoleDto](docs/ApiResponseRoleDto.md)
 - [ApiResponseSalesOrderDto](docs/ApiResponseSalesOrderDto.md)
 - [ApiResponseSalesTargetDto](docs/ApiResponseSalesTargetDto.md)
 - [ApiResponseStockSummaryDto](docs/ApiResponseStockSummaryDto.md)
 - [ApiResponseString](docs/ApiResponseString.md)
 - [ApiResponseSupplierResponse](docs/ApiResponseSupplierResponse.md)
 - [ApiResponseTrialBalanceDto](docs/ApiResponseTrialBalanceDto.md)
 - [ApiResponseUserDto](docs/ApiResponseUserDto.md)
 - [ApiResponseWorkforceInsights](docs/ApiResponseWorkforceInsights.md)
 - [ApproveOrderRequest](docs/ApproveOrderRequest.md)
 - [AuditDigestResponse](docs/AuditDigestResponse.md)
 - [AuthResponse](docs/AuthResponse.md)
 - [AutomationRun](docs/AutomationRun.md)
 - [BadDebtWriteOffRequest](docs/BadDebtWriteOffRequest.md)
 - [BalanceSheetDto](docs/BalanceSheetDto.md)
 - [BalanceWarningDto](docs/BalanceWarningDto.md)
 - [BankReconciliationItemDto](docs/BankReconciliationItemDto.md)
 - [BankReconciliationRequest](docs/BankReconciliationRequest.md)
 - [BankReconciliationSummaryDto](docs/BankReconciliationSummaryDto.md)
 - [CancelRequest](docs/CancelRequest.md)
 - [CashFlowDto](docs/CashFlowDto.md)
 - [CatalogImportResponse](docs/CatalogImportResponse.md)
 - [ChangePasswordRequest](docs/ChangePasswordRequest.md)
 - [CogsPostingDto](docs/CogsPostingDto.md)
 - [CompanyDto](docs/CompanyDto.md)
 - [CompanyRequest](docs/CompanyRequest.md)
 - [CostAllocationRequest](docs/CostAllocationRequest.md)
 - [CostAllocationResponse](docs/CostAllocationResponse.md)
 - [CostBreakdownDto](docs/CostBreakdownDto.md)
 - [CreateDealerRequest](docs/CreateDealerRequest.md)
 - [CreateRoleRequest](docs/CreateRoleRequest.md)
 - [CreateUserRequest](docs/CreateUserRequest.md)
 - [CreditNoteRequest](docs/CreditNoteRequest.md)
 - [CreditRequestDto](docs/CreditRequestDto.md)
 - [CreditRequestRequest](docs/CreditRequestRequest.md)
 - [DashboardInsights](docs/DashboardInsights.md)
 - [DealerLookupResponse](docs/DealerLookupResponse.md)
 - [DealerReceiptRequest](docs/DealerReceiptRequest.md)
 - [DealerResponse](docs/DealerResponse.md)
 - [DealerSettlementRequest](docs/DealerSettlementRequest.md)
 - [DebitNoteRequest](docs/DebitNoteRequest.md)
 - [DispatchConfirmRequest](docs/DispatchConfirmRequest.md)
 - [DispatchConfirmResponse](docs/DispatchConfirmResponse.md)
 - [DispatchLine](docs/DispatchLine.md)
 - [DispatchRequest](docs/DispatchRequest.md)
 - [EmployeeDto](docs/EmployeeDto.md)
 - [EmployeeRequest](docs/EmployeeRequest.md)
 - [FactoryDashboardDto](docs/FactoryDashboardDto.md)
 - [FactoryTaskDto](docs/FactoryTaskDto.md)
 - [FactoryTaskRequest](docs/FactoryTaskRequest.md)
 - [ForgotPasswordRequest](docs/ForgotPasswordRequest.md)
 - [GstReturnDto](docs/GstReturnDto.md)
 - [GstReturnDtoPeriod](docs/GstReturnDtoPeriod.md)
 - [HighlightMetric](docs/HighlightMetric.md)
 - [HrPulseMetric](docs/HrPulseMetric.md)
 - [ImportError](docs/ImportError.md)
 - [InventoryAdjustmentDto](docs/InventoryAdjustmentDto.md)
 - [InventoryAdjustmentLineDto](docs/InventoryAdjustmentLineDto.md)
 - [InventoryAdjustmentRequest](docs/InventoryAdjustmentRequest.md)
 - [InventoryCountRequest](docs/InventoryCountRequest.md)
 - [InventoryCountResponse](docs/InventoryCountResponse.md)
 - [InventoryRevaluationRequest](docs/InventoryRevaluationRequest.md)
 - [InventoryStockSnapshot](docs/InventoryStockSnapshot.md)
 - [InventoryValuationDto](docs/InventoryValuationDto.md)
 - [InvoiceDto](docs/InvoiceDto.md)
 - [InvoiceLineDto](docs/InvoiceLineDto.md)
 - [JournalEntryDto](docs/JournalEntryDto.md)
 - [JournalEntryRequest](docs/JournalEntryRequest.md)
 - [JournalEntryReversalRequest](docs/JournalEntryReversalRequest.md)
 - [JournalLineDto](docs/JournalLineDto.md)
 - [JournalLineRequest](docs/JournalLineRequest.md)
 - [LandedCostRequest](docs/LandedCostRequest.md)
 - [LeaveRequestDto](docs/LeaveRequestDto.md)
 - [LeaveRequestRequest](docs/LeaveRequestRequest.md)
 - [LineRequest](docs/LineRequest.md)
 - [LoginRequest](docs/LoginRequest.md)
 - [MaterialUsageRequest](docs/MaterialUsageRequest.md)
 - [MeResponse](docs/MeResponse.md)
 - [MfaActivateRequest](docs/MfaActivateRequest.md)
 - [MfaDisableRequest](docs/MfaDisableRequest.md)
 - [MfaSetupResponse](docs/MfaSetupResponse.md)
 - [MfaStatusResponse](docs/MfaStatusResponse.md)
 - [MonthEndChecklistDto](docs/MonthEndChecklistDto.md)
 - [MonthEndChecklistItemDto](docs/MonthEndChecklistItemDto.md)
 - [MonthEndChecklistUpdateRequest](docs/MonthEndChecklistUpdateRequest.md)
 - [MonthlyProductionCostDto](docs/MonthlyProductionCostDto.md)
 - [OperationsInsights](docs/OperationsInsights.md)
 - [OperationsSummary](docs/OperationsSummary.md)
 - [OrderFulfillmentRequest](docs/OrderFulfillmentRequest.md)
 - [PackingLineRequest](docs/PackingLineRequest.md)
 - [PackingRecordDto](docs/PackingRecordDto.md)
 - [PackingRequest](docs/PackingRequest.md)
 - [PartnerSettlementResponse](docs/PartnerSettlementResponse.md)
 - [PartnerStatementResponse](docs/PartnerStatementResponse.md)
 - [PayrollPaymentRequest](docs/PayrollPaymentRequest.md)
 - [PayrollRunDto](docs/PayrollRunDto.md)
 - [PayrollRunRequest](docs/PayrollRunRequest.md)
 - [PerformanceLeader](docs/PerformanceLeader.md)
 - [PermissionDto](docs/PermissionDto.md)
 - [PipelineStage](docs/PipelineStage.md)
 - [ProductCreateRequest](docs/ProductCreateRequest.md)
 - [ProductUpdateRequest](docs/ProductUpdateRequest.md)
 - [ProductionBatchDto](docs/ProductionBatchDto.md)
 - [ProductionBatchRequest](docs/ProductionBatchRequest.md)
 - [ProductionBrandDto](docs/ProductionBrandDto.md)
 - [ProductionLogDetailDto](docs/ProductionLogDetailDto.md)
 - [ProductionLogDto](docs/ProductionLogDto.md)
 - [ProductionLogMaterialDto](docs/ProductionLogMaterialDto.md)
 - [ProductionLogRequest](docs/ProductionLogRequest.md)
 - [ProductionPlanDto](docs/ProductionPlanDto.md)
 - [ProductionPlanRequest](docs/ProductionPlanRequest.md)
 - [ProductionProductDto](docs/ProductionProductDto.md)
 - [ProfileResponse](docs/ProfileResponse.md)
 - [ProfitLossDto](docs/ProfitLossDto.md)
 - [PromotionDto](docs/PromotionDto.md)
 - [PromotionRequest](docs/PromotionRequest.md)
 - [PurchaseReturnRequest](docs/PurchaseReturnRequest.md)
 - [RawMaterialBatchDto](docs/RawMaterialBatchDto.md)
 - [RawMaterialBatchRequest](docs/RawMaterialBatchRequest.md)
 - [RawMaterialDto](docs/RawMaterialDto.md)
 - [RawMaterialIntakeRequest](docs/RawMaterialIntakeRequest.md)
 - [RawMaterialPurchaseLineRequest](docs/RawMaterialPurchaseLineRequest.md)
 - [RawMaterialPurchaseLineResponse](docs/RawMaterialPurchaseLineResponse.md)
 - [RawMaterialPurchaseRequest](docs/RawMaterialPurchaseRequest.md)
 - [RawMaterialPurchaseResponse](docs/RawMaterialPurchaseResponse.md)
 - [RawMaterialRequest](docs/RawMaterialRequest.md)
 - [ReconciliationDashboardDto](docs/ReconciliationDashboardDto.md)
 - [ReconciliationSummaryDto](docs/ReconciliationSummaryDto.md)
 - [RefreshTokenRequest](docs/RefreshTokenRequest.md)
 - [ResetPasswordRequest](docs/ResetPasswordRequest.md)
 - [ReturnLine](docs/ReturnLine.md)
 - [RoleDto](docs/RoleDto.md)
 - [Row](docs/Row.md)
 - [SalesOrderDto](docs/SalesOrderDto.md)
 - [SalesOrderItemDto](docs/SalesOrderItemDto.md)
 - [SalesOrderItemRequest](docs/SalesOrderItemRequest.md)
 - [SalesOrderRequest](docs/SalesOrderRequest.md)
 - [SalesReturnRequest](docs/SalesReturnRequest.md)
 - [SalesTargetDto](docs/SalesTargetDto.md)
 - [SalesTargetRequest](docs/SalesTargetRequest.md)
 - [SettlementAllocationRequest](docs/SettlementAllocationRequest.md)
 - [SquadSummary](docs/SquadSummary.md)
 - [StatementTransactionDto](docs/StatementTransactionDto.md)
 - [StatusRequest](docs/StatusRequest.md)
 - [StockSummaryDto](docs/StockSummaryDto.md)
 - [SupplierPaymentRequest](docs/SupplierPaymentRequest.md)
 - [SupplierRequest](docs/SupplierRequest.md)
 - [SupplierResponse](docs/SupplierResponse.md)
 - [SupplierSettlementRequest](docs/SupplierSettlementRequest.md)
 - [SupplyAlert](docs/SupplyAlert.md)
 - [SwitchCompanyRequest](docs/SwitchCompanyRequest.md)
 - [TrialBalanceDto](docs/TrialBalanceDto.md)
 - [UnpackedBatchDto](docs/UnpackedBatchDto.md)
 - [UpcomingMoment](docs/UpcomingMoment.md)
 - [UpdateProfileRequest](docs/UpdateProfileRequest.md)
 - [UpdateUserRequest](docs/UpdateUserRequest.md)
 - [UserDto](docs/UserDto.md)
 - [WastageReportDto](docs/WastageReportDto.md)
 - [WipAdjustmentRequest](docs/WipAdjustmentRequest.md)
 - [WorkforceInsights](docs/WorkforceInsights.md)


<a id="documentation-for-authorization"></a>
## Documentation For Authorization

Endpoints do not require authorization.

