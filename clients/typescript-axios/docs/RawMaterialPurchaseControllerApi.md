# RawMaterialPurchaseControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**createPurchase**](#createpurchase) | **POST** /api/v1/purchasing/raw-material-purchases | |
|[**getPurchase**](#getpurchase) | **GET** /api/v1/purchasing/raw-material-purchases/{id} | |
|[**listPurchases**](#listpurchases) | **GET** /api/v1/purchasing/raw-material-purchases | |
|[**recordPurchaseReturn**](#recordpurchasereturn) | **POST** /api/v1/purchasing/raw-material-purchases/returns | |

# **createPurchase**
> ApiResponseRawMaterialPurchaseResponse createPurchase(rawMaterialPurchaseRequest)


### Example

```typescript
import {
    RawMaterialPurchaseControllerApi,
    Configuration,
    RawMaterialPurchaseRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new RawMaterialPurchaseControllerApi(configuration);

let rawMaterialPurchaseRequest: RawMaterialPurchaseRequest; //

const { status, data } = await apiInstance.createPurchase(
    rawMaterialPurchaseRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **rawMaterialPurchaseRequest** | **RawMaterialPurchaseRequest**|  | |


### Return type

**ApiResponseRawMaterialPurchaseResponse**

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

# **getPurchase**
> ApiResponseRawMaterialPurchaseResponse getPurchase()


### Example

```typescript
import {
    RawMaterialPurchaseControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new RawMaterialPurchaseControllerApi(configuration);

let id: number; // (default to undefined)

const { status, data } = await apiInstance.getPurchase(
    id
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseRawMaterialPurchaseResponse**

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

# **listPurchases**
> ApiResponseListRawMaterialPurchaseResponse listPurchases()


### Example

```typescript
import {
    RawMaterialPurchaseControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new RawMaterialPurchaseControllerApi(configuration);

const { status, data } = await apiInstance.listPurchases();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListRawMaterialPurchaseResponse**

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

# **recordPurchaseReturn**
> ApiResponseJournalEntryDto recordPurchaseReturn(purchaseReturnRequest)


### Example

```typescript
import {
    RawMaterialPurchaseControllerApi,
    Configuration,
    PurchaseReturnRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new RawMaterialPurchaseControllerApi(configuration);

let purchaseReturnRequest: PurchaseReturnRequest; //

const { status, data } = await apiInstance.recordPurchaseReturn(
    purchaseReturnRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **purchaseReturnRequest** | **PurchaseReturnRequest**|  | |


### Return type

**ApiResponseJournalEntryDto**

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

