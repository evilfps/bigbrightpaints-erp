# FactoryControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**allocateCosts**](#allocatecosts) | **POST** /api/v1/factory/cost-allocation | |
|[**batches1**](#batches1) | **GET** /api/v1/factory/production-batches | |
|[**createPlan**](#createplan) | **POST** /api/v1/factory/production-plans | |
|[**createTask**](#createtask) | **POST** /api/v1/factory/tasks | |
|[**dashboard1**](#dashboard1) | **GET** /api/v1/factory/dashboard | |
|[**deletePlan**](#deleteplan) | **DELETE** /api/v1/factory/production-plans/{id} | |
|[**logBatch**](#logbatch) | **POST** /api/v1/factory/production-batches | |
|[**plans**](#plans) | **GET** /api/v1/factory/production-plans | |
|[**tasks**](#tasks) | **GET** /api/v1/factory/tasks | |
|[**updatePlan**](#updateplan) | **PUT** /api/v1/factory/production-plans/{id} | |
|[**updatePlanStatus**](#updateplanstatus) | **PATCH** /api/v1/factory/production-plans/{id}/status | |
|[**updateTask**](#updatetask) | **PUT** /api/v1/factory/tasks/{id} | |

# **allocateCosts**
> ApiResponseCostAllocationResponse allocateCosts(costAllocationRequest)


### Example

```typescript
import {
    FactoryControllerApi,
    Configuration,
    CostAllocationRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new FactoryControllerApi(configuration);

let costAllocationRequest: CostAllocationRequest; //

const { status, data } = await apiInstance.allocateCosts(
    costAllocationRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **costAllocationRequest** | **CostAllocationRequest**|  | |


### Return type

**ApiResponseCostAllocationResponse**

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

# **batches1**
> ApiResponseListProductionBatchDto batches1()


### Example

```typescript
import {
    FactoryControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new FactoryControllerApi(configuration);

const { status, data } = await apiInstance.batches1();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListProductionBatchDto**

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

# **createPlan**
> ApiResponseProductionPlanDto createPlan(productionPlanRequest)


### Example

```typescript
import {
    FactoryControllerApi,
    Configuration,
    ProductionPlanRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new FactoryControllerApi(configuration);

let productionPlanRequest: ProductionPlanRequest; //

const { status, data } = await apiInstance.createPlan(
    productionPlanRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **productionPlanRequest** | **ProductionPlanRequest**|  | |


### Return type

**ApiResponseProductionPlanDto**

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

# **createTask**
> ApiResponseFactoryTaskDto createTask(factoryTaskRequest)


### Example

```typescript
import {
    FactoryControllerApi,
    Configuration,
    FactoryTaskRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new FactoryControllerApi(configuration);

let factoryTaskRequest: FactoryTaskRequest; //

const { status, data } = await apiInstance.createTask(
    factoryTaskRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **factoryTaskRequest** | **FactoryTaskRequest**|  | |


### Return type

**ApiResponseFactoryTaskDto**

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

# **dashboard1**
> ApiResponseFactoryDashboardDto dashboard1()


### Example

```typescript
import {
    FactoryControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new FactoryControllerApi(configuration);

const { status, data } = await apiInstance.dashboard1();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseFactoryDashboardDto**

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

# **deletePlan**
> deletePlan()


### Example

```typescript
import {
    FactoryControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new FactoryControllerApi(configuration);

let id: number; // (default to undefined)

const { status, data } = await apiInstance.deletePlan(
    id
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **id** | [**number**] |  | defaults to undefined|


### Return type

void (empty response body)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: Not defined


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | OK |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **logBatch**
> ApiResponseProductionBatchDto logBatch(productionBatchRequest)


### Example

```typescript
import {
    FactoryControllerApi,
    Configuration,
    ProductionBatchRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new FactoryControllerApi(configuration);

let productionBatchRequest: ProductionBatchRequest; //
let planId: number; // (optional) (default to undefined)

const { status, data } = await apiInstance.logBatch(
    productionBatchRequest,
    planId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **productionBatchRequest** | **ProductionBatchRequest**|  | |
| **planId** | [**number**] |  | (optional) defaults to undefined|


### Return type

**ApiResponseProductionBatchDto**

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

# **plans**
> ApiResponseListProductionPlanDto plans()


### Example

```typescript
import {
    FactoryControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new FactoryControllerApi(configuration);

const { status, data } = await apiInstance.plans();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListProductionPlanDto**

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

# **tasks**
> ApiResponseListFactoryTaskDto tasks()


### Example

```typescript
import {
    FactoryControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new FactoryControllerApi(configuration);

const { status, data } = await apiInstance.tasks();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListFactoryTaskDto**

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

# **updatePlan**
> ApiResponseProductionPlanDto updatePlan(productionPlanRequest)


### Example

```typescript
import {
    FactoryControllerApi,
    Configuration,
    ProductionPlanRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new FactoryControllerApi(configuration);

let id: number; // (default to undefined)
let productionPlanRequest: ProductionPlanRequest; //

const { status, data } = await apiInstance.updatePlan(
    id,
    productionPlanRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **productionPlanRequest** | **ProductionPlanRequest**|  | |
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseProductionPlanDto**

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

# **updatePlanStatus**
> ApiResponseProductionPlanDto updatePlanStatus(statusRequest)


### Example

```typescript
import {
    FactoryControllerApi,
    Configuration,
    StatusRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new FactoryControllerApi(configuration);

let id: number; // (default to undefined)
let statusRequest: StatusRequest; //

const { status, data } = await apiInstance.updatePlanStatus(
    id,
    statusRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **statusRequest** | **StatusRequest**|  | |
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseProductionPlanDto**

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

# **updateTask**
> ApiResponseFactoryTaskDto updateTask(factoryTaskRequest)


### Example

```typescript
import {
    FactoryControllerApi,
    Configuration,
    FactoryTaskRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new FactoryControllerApi(configuration);

let id: number; // (default to undefined)
let factoryTaskRequest: FactoryTaskRequest; //

const { status, data } = await apiInstance.updateTask(
    id,
    factoryTaskRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **factoryTaskRequest** | **FactoryTaskRequest**|  | |
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseFactoryTaskDto**

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

