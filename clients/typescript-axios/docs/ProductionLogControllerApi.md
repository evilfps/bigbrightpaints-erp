# ProductionLogControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**create**](#create) | **POST** /api/v1/factory/production/logs | |
|[**detail**](#detail) | **GET** /api/v1/factory/production/logs/{id} | |
|[**list**](#list) | **GET** /api/v1/factory/production/logs | |

# **create**
> ApiResponseProductionLogDetailDto create(productionLogRequest)


### Example

```typescript
import {
    ProductionLogControllerApi,
    Configuration,
    ProductionLogRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new ProductionLogControllerApi(configuration);

let productionLogRequest: ProductionLogRequest; //

const { status, data } = await apiInstance.create(
    productionLogRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **productionLogRequest** | **ProductionLogRequest**|  | |


### Return type

**ApiResponseProductionLogDetailDto**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: */*


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | OK |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **detail**
> ApiResponseProductionLogDetailDto detail()


### Example

```typescript
import {
    ProductionLogControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new ProductionLogControllerApi(configuration);

let id: number; // (default to undefined)

const { status, data } = await apiInstance.detail(
    id
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseProductionLogDetailDto**

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

# **list**
> ApiResponseListProductionLogDto list()


### Example

```typescript
import {
    ProductionLogControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new ProductionLogControllerApi(configuration);

const { status, data } = await apiInstance.list();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListProductionLogDto**

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

