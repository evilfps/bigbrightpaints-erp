# ReportControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**accountStatement**](#accountstatement) | **GET** /api/v1/reports/account-statement | |
|[**agedDebtors**](#ageddebtors) | **GET** /api/v1/accounting/reports/aged-debtors | |
|[**balanceSheet**](#balancesheet) | **GET** /api/v1/reports/balance-sheet | |
|[**balanceWarnings**](#balancewarnings) | **GET** /api/v1/reports/balance-warnings | |
|[**cashFlow**](#cashflow) | **GET** /api/v1/reports/cash-flow | |
|[**costBreakdown**](#costbreakdown) | **GET** /api/v1/reports/production-logs/{id}/cost-breakdown | |
|[**inventoryReconciliation**](#inventoryreconciliation) | **GET** /api/v1/reports/inventory-reconciliation | |
|[**inventoryValuation**](#inventoryvaluation) | **GET** /api/v1/reports/inventory-valuation | |
|[**monthlyProductionCosts**](#monthlyproductioncosts) | **GET** /api/v1/reports/monthly-production-costs | |
|[**profitLoss**](#profitloss) | **GET** /api/v1/reports/profit-loss | |
|[**reconciliationDashboard**](#reconciliationdashboard) | **GET** /api/v1/reports/reconciliation-dashboard | |
|[**trialBalance**](#trialbalance) | **GET** /api/v1/reports/trial-balance | |
|[**wastageReport**](#wastagereport) | **GET** /api/v1/reports/wastage | |

# **accountStatement**
> ApiResponseListAccountStatementEntryDto accountStatement()


### Example

```typescript
import {
    ReportControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new ReportControllerApi(configuration);

const { status, data } = await apiInstance.accountStatement();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListAccountStatementEntryDto**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: */*


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | OK |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **agedDebtors**
> ApiResponseListAgedDebtorDto agedDebtors()


### Example

```typescript
import {
    ReportControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new ReportControllerApi(configuration);

const { status, data } = await apiInstance.agedDebtors();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListAgedDebtorDto**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: */*


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | OK |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **balanceSheet**
> ApiResponseBalanceSheetDto balanceSheet()


### Example

```typescript
import {
    ReportControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new ReportControllerApi(configuration);

const { status, data } = await apiInstance.balanceSheet();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseBalanceSheetDto**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: */*


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | OK |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **balanceWarnings**
> ApiResponseListBalanceWarningDto balanceWarnings()


### Example

```typescript
import {
    ReportControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new ReportControllerApi(configuration);

const { status, data } = await apiInstance.balanceWarnings();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListBalanceWarningDto**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: */*


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | OK |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **cashFlow**
> ApiResponseCashFlowDto cashFlow()


### Example

```typescript
import {
    ReportControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new ReportControllerApi(configuration);

const { status, data } = await apiInstance.cashFlow();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseCashFlowDto**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: */*


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | OK |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **costBreakdown**
> ApiResponseCostBreakdownDto costBreakdown()


### Example

```typescript
import {
    ReportControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new ReportControllerApi(configuration);

let id: number; // (default to undefined)

const { status, data } = await apiInstance.costBreakdown(
    id
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseCostBreakdownDto**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: */*


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | OK |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **inventoryReconciliation**
> ApiResponseReconciliationSummaryDto inventoryReconciliation()


### Example

```typescript
import {
    ReportControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new ReportControllerApi(configuration);

const { status, data } = await apiInstance.inventoryReconciliation();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseReconciliationSummaryDto**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: */*


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | OK |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **inventoryValuation**
> ApiResponseInventoryValuationDto inventoryValuation()


### Example

```typescript
import {
    ReportControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new ReportControllerApi(configuration);

const { status, data } = await apiInstance.inventoryValuation();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseInventoryValuationDto**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: */*


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | OK |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **monthlyProductionCosts**
> ApiResponseMonthlyProductionCostDto monthlyProductionCosts()


### Example

```typescript
import {
    ReportControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new ReportControllerApi(configuration);

let year: number; // (default to undefined)
let month: number; // (default to undefined)

const { status, data } = await apiInstance.monthlyProductionCosts(
    year,
    month
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **year** | [**number**] |  | defaults to undefined|
| **month** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseMonthlyProductionCostDto**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: */*


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | OK |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **profitLoss**
> ApiResponseProfitLossDto profitLoss()


### Example

```typescript
import {
    ReportControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new ReportControllerApi(configuration);

const { status, data } = await apiInstance.profitLoss();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseProfitLossDto**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: */*


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | OK |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **reconciliationDashboard**
> ApiResponseReconciliationDashboardDto reconciliationDashboard()


### Example

```typescript
import {
    ReportControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new ReportControllerApi(configuration);

let bankAccountId: number; // (default to undefined)
let statementBalance: number; // (optional) (default to undefined)

const { status, data } = await apiInstance.reconciliationDashboard(
    bankAccountId,
    statementBalance
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **bankAccountId** | [**number**] |  | defaults to undefined|
| **statementBalance** | [**number**] |  | (optional) defaults to undefined|


### Return type

**ApiResponseReconciliationDashboardDto**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: */*


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | OK |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **trialBalance**
> ApiResponseTrialBalanceDto trialBalance()


### Example

```typescript
import {
    ReportControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new ReportControllerApi(configuration);

const { status, data } = await apiInstance.trialBalance();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseTrialBalanceDto**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: */*


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | OK |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **wastageReport**
> ApiResponseListWastageReportDto wastageReport()


### Example

```typescript
import {
    ReportControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new ReportControllerApi(configuration);

const { status, data } = await apiInstance.wastageReport();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListWastageReportDto**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: */*


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | OK |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

