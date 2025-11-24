# PackingControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**completePacking**](#completepacking) | **POST** /api/v1/factory/packing-records/{productionLogId}/complete | |
|[**listUnpackedBatches**](#listunpackedbatches) | **GET** /api/v1/factory/unpacked-batches | |
|[**packingHistory**](#packinghistory) | **GET** /api/v1/factory/production-logs/{productionLogId}/packing-history | |
|[**recordPacking**](#recordpacking) | **POST** /api/v1/factory/packing-records | |

# **completePacking**
> ApiResponseProductionLogDetailDto completePacking()


### Example

```typescript
import {
    PackingControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new PackingControllerApi(configuration);

let productionLogId: number; // (default to undefined)

const { status, data } = await apiInstance.completePacking(
    productionLogId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **productionLogId** | [**number**] |  | defaults to undefined|


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

# **listUnpackedBatches**
> ApiResponseListUnpackedBatchDto listUnpackedBatches()


### Example

```typescript
import {
    PackingControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new PackingControllerApi(configuration);

const { status, data } = await apiInstance.listUnpackedBatches();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListUnpackedBatchDto**

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

# **packingHistory**
> ApiResponseListPackingRecordDto packingHistory()


### Example

```typescript
import {
    PackingControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new PackingControllerApi(configuration);

let productionLogId: number; // (default to undefined)

const { status, data } = await apiInstance.packingHistory(
    productionLogId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **productionLogId** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseListPackingRecordDto**

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

# **recordPacking**
> ApiResponseProductionLogDetailDto recordPacking(packingRequest)


### Example

```typescript
import {
    PackingControllerApi,
    Configuration,
    PackingRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new PackingControllerApi(configuration);

let packingRequest: PackingRequest; //

const { status, data } = await apiInstance.recordPacking(
    packingRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **packingRequest** | **PackingRequest**|  | |


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

