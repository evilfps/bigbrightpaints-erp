# OrchestratorControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**approveOrder**](#approveorder) | **POST** /api/v1/orchestrator/orders/{orderId}/approve | |
|[**dispatch**](#dispatch) | **POST** /api/v1/orchestrator/factory/dispatch/{batchId} | |
|[**dispatchOrder**](#dispatchorder) | **POST** /api/v1/orchestrator/dispatch | |
|[**eventHealth**](#eventhealth) | **GET** /api/v1/orchestrator/health/events | |
|[**fulfillOrder**](#fulfillorder) | **POST** /api/v1/orchestrator/orders/{orderId}/fulfillment | |
|[**integrationsHealth**](#integrationshealth) | **GET** /api/v1/orchestrator/health/integrations | |
|[**runPayroll**](#runpayroll) | **POST** /api/v1/orchestrator/payroll/run | |
|[**trace**](#trace) | **GET** /api/v1/orchestrator/traces/{traceId} | |

# **approveOrder**
> { [key: string]: object; } approveOrder(approveOrderRequest)


### Example

```typescript
import {
    OrchestratorControllerApi,
    Configuration,
    ApproveOrderRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new OrchestratorControllerApi(configuration);

let orderId: string; // (default to undefined)
let xCompanyId: string; // (default to undefined)
let approveOrderRequest: ApproveOrderRequest; //

const { status, data } = await apiInstance.approveOrder(
    orderId,
    xCompanyId,
    approveOrderRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **approveOrderRequest** | **ApproveOrderRequest**|  | |
| **orderId** | [**string**] |  | defaults to undefined|
| **xCompanyId** | [**string**] |  | defaults to undefined|


### Return type

**{ [key: string]: object; }**

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

# **dispatch**
> { [key: string]: object; } dispatch(dispatchRequest)


### Example

```typescript
import {
    OrchestratorControllerApi,
    Configuration,
    DispatchRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new OrchestratorControllerApi(configuration);

let batchId: string; // (default to undefined)
let xCompanyId: string; // (default to undefined)
let dispatchRequest: DispatchRequest; //

const { status, data } = await apiInstance.dispatch(
    batchId,
    xCompanyId,
    dispatchRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **dispatchRequest** | **DispatchRequest**|  | |
| **batchId** | [**string**] |  | defaults to undefined|
| **xCompanyId** | [**string**] |  | defaults to undefined|


### Return type

**{ [key: string]: object; }**

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

# **dispatchOrder**
> { [key: string]: object; } dispatchOrder(requestBody)


### Example

```typescript
import {
    OrchestratorControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new OrchestratorControllerApi(configuration);

let xCompanyId: string; // (default to undefined)
let requestBody: { [key: string]: object; }; //

const { status, data } = await apiInstance.dispatchOrder(
    xCompanyId,
    requestBody
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **requestBody** | **{ [key: string]: object; }**|  | |
| **xCompanyId** | [**string**] |  | defaults to undefined|


### Return type

**{ [key: string]: object; }**

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

# **eventHealth**
> { [key: string]: object; } eventHealth()


### Example

```typescript
import {
    OrchestratorControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new OrchestratorControllerApi(configuration);

const { status, data } = await apiInstance.eventHealth();
```

### Parameters
This endpoint does not have any parameters.


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

# **fulfillOrder**
> { [key: string]: object; } fulfillOrder(orderFulfillmentRequest)


### Example

```typescript
import {
    OrchestratorControllerApi,
    Configuration,
    OrderFulfillmentRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new OrchestratorControllerApi(configuration);

let orderId: string; // (default to undefined)
let xCompanyId: string; // (default to undefined)
let orderFulfillmentRequest: OrderFulfillmentRequest; //

const { status, data } = await apiInstance.fulfillOrder(
    orderId,
    xCompanyId,
    orderFulfillmentRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **orderFulfillmentRequest** | **OrderFulfillmentRequest**|  | |
| **orderId** | [**string**] |  | defaults to undefined|
| **xCompanyId** | [**string**] |  | defaults to undefined|


### Return type

**{ [key: string]: object; }**

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

# **integrationsHealth**
> { [key: string]: object; } integrationsHealth()


### Example

```typescript
import {
    OrchestratorControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new OrchestratorControllerApi(configuration);

const { status, data } = await apiInstance.integrationsHealth();
```

### Parameters
This endpoint does not have any parameters.


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

# **runPayroll**
> { [key: string]: object; } runPayroll(payrollRunRequest)


### Example

```typescript
import {
    OrchestratorControllerApi,
    Configuration,
    PayrollRunRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new OrchestratorControllerApi(configuration);

let xCompanyId: string; // (default to undefined)
let payrollRunRequest: PayrollRunRequest; //

const { status, data } = await apiInstance.runPayroll(
    xCompanyId,
    payrollRunRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **payrollRunRequest** | **PayrollRunRequest**|  | |
| **xCompanyId** | [**string**] |  | defaults to undefined|


### Return type

**{ [key: string]: object; }**

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

# **trace**
> { [key: string]: object; } trace()


### Example

```typescript
import {
    OrchestratorControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new OrchestratorControllerApi(configuration);

let traceId: string; // (default to undefined)

const { status, data } = await apiInstance.trace(
    traceId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **traceId** | [**string**] |  | defaults to undefined|


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

