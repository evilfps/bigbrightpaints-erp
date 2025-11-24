# DashboardControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**adminDashboard**](#admindashboard) | **GET** /api/v1/orchestrator/dashboard/admin | |
|[**factoryDashboard**](#factorydashboard) | **GET** /api/v1/orchestrator/dashboard/factory | |
|[**financeDashboard**](#financedashboard) | **GET** /api/v1/orchestrator/dashboard/finance | |

# **adminDashboard**
> { [key: string]: object; } adminDashboard()


### Example

```typescript
import {
    DashboardControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new DashboardControllerApi(configuration);

let xCompanyId: string; // (default to undefined)

const { status, data } = await apiInstance.adminDashboard(
    xCompanyId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **xCompanyId** | [**string**] |  | defaults to undefined|


### Return type

**{ [key: string]: object; }**

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

# **factoryDashboard**
> { [key: string]: object; } factoryDashboard()


### Example

```typescript
import {
    DashboardControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new DashboardControllerApi(configuration);

let xCompanyId: string; // (default to undefined)

const { status, data } = await apiInstance.factoryDashboard(
    xCompanyId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **xCompanyId** | [**string**] |  | defaults to undefined|


### Return type

**{ [key: string]: object; }**

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

# **financeDashboard**
> { [key: string]: object; } financeDashboard()


### Example

```typescript
import {
    DashboardControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new DashboardControllerApi(configuration);

let xCompanyId: string; // (default to undefined)

const { status, data } = await apiInstance.financeDashboard(
    xCompanyId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **xCompanyId** | [**string**] |  | defaults to undefined|


### Return type

**{ [key: string]: object; }**

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

