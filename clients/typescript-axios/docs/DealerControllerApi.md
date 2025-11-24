# DealerControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**createDealer**](#createdealer) | **POST** /api/v1/dealers | |
|[**dealerLedger**](#dealerledger) | **GET** /api/v1/dealers/{dealerId}/ledger | |
|[**holdIfOverdue**](#holdifoverdue) | **POST** /api/v1/dealers/{dealerId}/dunning/hold | |
|[**listDealers**](#listdealers) | **GET** /api/v1/dealers | |
|[**searchDealers**](#searchdealers) | **GET** /api/v1/dealers/search | |

# **createDealer**
> ApiResponseDealerResponse createDealer(createDealerRequest)


### Example

```typescript
import {
    DealerControllerApi,
    Configuration,
    CreateDealerRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new DealerControllerApi(configuration);

let createDealerRequest: CreateDealerRequest; //

const { status, data } = await apiInstance.createDealer(
    createDealerRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **createDealerRequest** | **CreateDealerRequest**|  | |


### Return type

**ApiResponseDealerResponse**

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

# **dealerLedger**
> ApiResponseMapStringObject dealerLedger()


### Example

```typescript
import {
    DealerControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new DealerControllerApi(configuration);

let dealerId: number; // (default to undefined)

const { status, data } = await apiInstance.dealerLedger(
    dealerId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **dealerId** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseMapStringObject**

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

# **holdIfOverdue**
> ApiResponseMapStringObject holdIfOverdue()


### Example

```typescript
import {
    DealerControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new DealerControllerApi(configuration);

let dealerId: number; // (default to undefined)
let overdueDays: number; // (optional) (default to 45)
let minAmount: number; // (optional) (default to 0)

const { status, data } = await apiInstance.holdIfOverdue(
    dealerId,
    overdueDays,
    minAmount
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **dealerId** | [**number**] |  | defaults to undefined|
| **overdueDays** | [**number**] |  | (optional) defaults to 45|
| **minAmount** | [**number**] |  | (optional) defaults to 0|


### Return type

**ApiResponseMapStringObject**

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

# **listDealers**
> ApiResponseListDealerResponse listDealers()


### Example

```typescript
import {
    DealerControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new DealerControllerApi(configuration);

const { status, data } = await apiInstance.listDealers();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListDealerResponse**

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

# **searchDealers**
> ApiResponseListDealerLookupResponse searchDealers()


### Example

```typescript
import {
    DealerControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new DealerControllerApi(configuration);

let query: string; // (optional) (default to '')

const { status, data } = await apiInstance.searchDealers(
    query
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **query** | [**string**] |  | (optional) defaults to ''|


### Return type

**ApiResponseListDealerLookupResponse**

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

