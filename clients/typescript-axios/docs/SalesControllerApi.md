# SalesControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**cancelOrder**](#cancelorder) | **POST** /api/v1/sales/orders/{id}/cancel | |
|[**confirmDispatch**](#confirmdispatch) | **POST** /api/v1/sales/dispatch/confirm | |
|[**confirmOrder**](#confirmorder) | **POST** /api/v1/sales/orders/{id}/confirm | |
|[**createCreditRequest**](#createcreditrequest) | **POST** /api/v1/sales/credit-requests | |
|[**createOrder**](#createorder) | **POST** /api/v1/sales/orders | |
|[**createPromotion**](#createpromotion) | **POST** /api/v1/sales/promotions | |
|[**createTarget**](#createtarget) | **POST** /api/v1/sales/targets | |
|[**creditRequests**](#creditrequests) | **GET** /api/v1/sales/credit-requests | |
|[**deleteOrder**](#deleteorder) | **DELETE** /api/v1/sales/orders/{id} | |
|[**deletePromotion**](#deletepromotion) | **DELETE** /api/v1/sales/promotions/{id} | |
|[**deleteTarget**](#deletetarget) | **DELETE** /api/v1/sales/targets/{id} | |
|[**orders**](#orders) | **GET** /api/v1/sales/orders | |
|[**promotions**](#promotions) | **GET** /api/v1/sales/promotions | |
|[**targets**](#targets) | **GET** /api/v1/sales/targets | |
|[**updateCreditRequest**](#updatecreditrequest) | **PUT** /api/v1/sales/credit-requests/{id} | |
|[**updateOrder**](#updateorder) | **PUT** /api/v1/sales/orders/{id} | |
|[**updatePromotion**](#updatepromotion) | **PUT** /api/v1/sales/promotions/{id} | |
|[**updateStatus**](#updatestatus) | **PATCH** /api/v1/sales/orders/{id}/status | |
|[**updateTarget**](#updatetarget) | **PUT** /api/v1/sales/targets/{id} | |

# **cancelOrder**
> ApiResponseSalesOrderDto cancelOrder()


### Example

```typescript
import {
    SalesControllerApi,
    Configuration,
    CancelRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SalesControllerApi(configuration);

let id: number; // (default to undefined)
let cancelRequest: CancelRequest; // (optional)

const { status, data } = await apiInstance.cancelOrder(
    id,
    cancelRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **cancelRequest** | **CancelRequest**|  | |
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseSalesOrderDto**

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

# **confirmDispatch**
> ApiResponseDispatchConfirmResponse confirmDispatch(dispatchConfirmRequest)


### Example

```typescript
import {
    SalesControllerApi,
    Configuration,
    DispatchConfirmRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SalesControllerApi(configuration);

let dispatchConfirmRequest: DispatchConfirmRequest; //

const { status, data } = await apiInstance.confirmDispatch(
    dispatchConfirmRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **dispatchConfirmRequest** | **DispatchConfirmRequest**|  | |


### Return type

**ApiResponseDispatchConfirmResponse**

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

# **confirmOrder**
> ApiResponseSalesOrderDto confirmOrder()


### Example

```typescript
import {
    SalesControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SalesControllerApi(configuration);

let id: number; // (default to undefined)

const { status, data } = await apiInstance.confirmOrder(
    id
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseSalesOrderDto**

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

# **createCreditRequest**
> ApiResponseCreditRequestDto createCreditRequest(creditRequestRequest)


### Example

```typescript
import {
    SalesControllerApi,
    Configuration,
    CreditRequestRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SalesControllerApi(configuration);

let creditRequestRequest: CreditRequestRequest; //

const { status, data } = await apiInstance.createCreditRequest(
    creditRequestRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **creditRequestRequest** | **CreditRequestRequest**|  | |


### Return type

**ApiResponseCreditRequestDto**

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

# **createOrder**
> ApiResponseSalesOrderDto createOrder(salesOrderRequest)


### Example

```typescript
import {
    SalesControllerApi,
    Configuration,
    SalesOrderRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SalesControllerApi(configuration);

let salesOrderRequest: SalesOrderRequest; //

const { status, data } = await apiInstance.createOrder(
    salesOrderRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **salesOrderRequest** | **SalesOrderRequest**|  | |


### Return type

**ApiResponseSalesOrderDto**

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

# **createPromotion**
> ApiResponsePromotionDto createPromotion(promotionRequest)


### Example

```typescript
import {
    SalesControllerApi,
    Configuration,
    PromotionRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SalesControllerApi(configuration);

let promotionRequest: PromotionRequest; //

const { status, data } = await apiInstance.createPromotion(
    promotionRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **promotionRequest** | **PromotionRequest**|  | |


### Return type

**ApiResponsePromotionDto**

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

# **createTarget**
> ApiResponseSalesTargetDto createTarget(salesTargetRequest)


### Example

```typescript
import {
    SalesControllerApi,
    Configuration,
    SalesTargetRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SalesControllerApi(configuration);

let salesTargetRequest: SalesTargetRequest; //

const { status, data } = await apiInstance.createTarget(
    salesTargetRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **salesTargetRequest** | **SalesTargetRequest**|  | |


### Return type

**ApiResponseSalesTargetDto**

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

# **creditRequests**
> ApiResponseListCreditRequestDto creditRequests()


### Example

```typescript
import {
    SalesControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SalesControllerApi(configuration);

const { status, data } = await apiInstance.creditRequests();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListCreditRequestDto**

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

# **deleteOrder**
> deleteOrder()


### Example

```typescript
import {
    SalesControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SalesControllerApi(configuration);

let id: number; // (default to undefined)

const { status, data } = await apiInstance.deleteOrder(
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

# **deletePromotion**
> deletePromotion()


### Example

```typescript
import {
    SalesControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SalesControllerApi(configuration);

let id: number; // (default to undefined)

const { status, data } = await apiInstance.deletePromotion(
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

# **deleteTarget**
> deleteTarget()


### Example

```typescript
import {
    SalesControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SalesControllerApi(configuration);

let id: number; // (default to undefined)

const { status, data } = await apiInstance.deleteTarget(
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

# **orders**
> ApiResponseListSalesOrderDto orders()


### Example

```typescript
import {
    SalesControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SalesControllerApi(configuration);

let status: string; // (optional) (default to undefined)

const { status, data } = await apiInstance.orders(
    status
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **status** | [**string**] |  | (optional) defaults to undefined|


### Return type

**ApiResponseListSalesOrderDto**

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

# **promotions**
> ApiResponseListPromotionDto promotions()


### Example

```typescript
import {
    SalesControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SalesControllerApi(configuration);

const { status, data } = await apiInstance.promotions();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListPromotionDto**

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

# **targets**
> ApiResponseListSalesTargetDto targets()


### Example

```typescript
import {
    SalesControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SalesControllerApi(configuration);

const { status, data } = await apiInstance.targets();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListSalesTargetDto**

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

# **updateCreditRequest**
> ApiResponseCreditRequestDto updateCreditRequest(creditRequestRequest)


### Example

```typescript
import {
    SalesControllerApi,
    Configuration,
    CreditRequestRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SalesControllerApi(configuration);

let id: number; // (default to undefined)
let creditRequestRequest: CreditRequestRequest; //

const { status, data } = await apiInstance.updateCreditRequest(
    id,
    creditRequestRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **creditRequestRequest** | **CreditRequestRequest**|  | |
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseCreditRequestDto**

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

# **updateOrder**
> ApiResponseSalesOrderDto updateOrder(salesOrderRequest)


### Example

```typescript
import {
    SalesControllerApi,
    Configuration,
    SalesOrderRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SalesControllerApi(configuration);

let id: number; // (default to undefined)
let salesOrderRequest: SalesOrderRequest; //

const { status, data } = await apiInstance.updateOrder(
    id,
    salesOrderRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **salesOrderRequest** | **SalesOrderRequest**|  | |
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseSalesOrderDto**

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

# **updatePromotion**
> ApiResponsePromotionDto updatePromotion(promotionRequest)


### Example

```typescript
import {
    SalesControllerApi,
    Configuration,
    PromotionRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SalesControllerApi(configuration);

let id: number; // (default to undefined)
let promotionRequest: PromotionRequest; //

const { status, data } = await apiInstance.updatePromotion(
    id,
    promotionRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **promotionRequest** | **PromotionRequest**|  | |
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponsePromotionDto**

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

# **updateStatus**
> ApiResponseSalesOrderDto updateStatus(statusRequest)


### Example

```typescript
import {
    SalesControllerApi,
    Configuration,
    StatusRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SalesControllerApi(configuration);

let id: number; // (default to undefined)
let statusRequest: StatusRequest; //

const { status, data } = await apiInstance.updateStatus(
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

**ApiResponseSalesOrderDto**

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

# **updateTarget**
> ApiResponseSalesTargetDto updateTarget(salesTargetRequest)


### Example

```typescript
import {
    SalesControllerApi,
    Configuration,
    SalesTargetRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SalesControllerApi(configuration);

let id: number; // (default to undefined)
let salesTargetRequest: SalesTargetRequest; //

const { status, data } = await apiInstance.updateTarget(
    id,
    salesTargetRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **salesTargetRequest** | **SalesTargetRequest**|  | |
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseSalesTargetDto**

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

