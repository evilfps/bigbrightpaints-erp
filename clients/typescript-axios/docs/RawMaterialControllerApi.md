# RawMaterialControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**batches**](#batches) | **GET** /api/v1/raw-material-batches/{rawMaterialId} | |
|[**createBatch**](#createbatch) | **POST** /api/v1/raw-material-batches/{rawMaterialId} | |
|[**createRawMaterial**](#createrawmaterial) | **POST** /api/v1/accounting/raw-materials | |
|[**deleteRawMaterial**](#deleterawmaterial) | **DELETE** /api/v1/accounting/raw-materials/{id} | |
|[**intake**](#intake) | **POST** /api/v1/raw-materials/intake | |
|[**inventory**](#inventory) | **GET** /api/v1/raw-materials/stock/inventory | |
|[**listRawMaterials**](#listrawmaterials) | **GET** /api/v1/accounting/raw-materials | |
|[**lowStock**](#lowstock) | **GET** /api/v1/raw-materials/stock/low-stock | |
|[**stockSummary**](#stocksummary) | **GET** /api/v1/raw-materials/stock | |
|[**updateRawMaterial**](#updaterawmaterial) | **PUT** /api/v1/accounting/raw-materials/{id} | |

# **batches**
> ApiResponseListRawMaterialBatchDto batches()


### Example

```typescript
import {
    RawMaterialControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new RawMaterialControllerApi(configuration);

let rawMaterialId: number; // (default to undefined)

const { status, data } = await apiInstance.batches(
    rawMaterialId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **rawMaterialId** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseListRawMaterialBatchDto**

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

# **createBatch**
> ApiResponseRawMaterialBatchDto createBatch(rawMaterialBatchRequest)


### Example

```typescript
import {
    RawMaterialControllerApi,
    Configuration,
    RawMaterialBatchRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new RawMaterialControllerApi(configuration);

let rawMaterialId: number; // (default to undefined)
let rawMaterialBatchRequest: RawMaterialBatchRequest; //

const { status, data } = await apiInstance.createBatch(
    rawMaterialId,
    rawMaterialBatchRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **rawMaterialBatchRequest** | **RawMaterialBatchRequest**|  | |
| **rawMaterialId** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseRawMaterialBatchDto**

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

# **createRawMaterial**
> ApiResponseRawMaterialDto createRawMaterial(rawMaterialRequest)


### Example

```typescript
import {
    RawMaterialControllerApi,
    Configuration,
    RawMaterialRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new RawMaterialControllerApi(configuration);

let rawMaterialRequest: RawMaterialRequest; //

const { status, data } = await apiInstance.createRawMaterial(
    rawMaterialRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **rawMaterialRequest** | **RawMaterialRequest**|  | |


### Return type

**ApiResponseRawMaterialDto**

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

# **deleteRawMaterial**
> deleteRawMaterial()


### Example

```typescript
import {
    RawMaterialControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new RawMaterialControllerApi(configuration);

let id: number; // (default to undefined)

const { status, data } = await apiInstance.deleteRawMaterial(
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

# **intake**
> ApiResponseRawMaterialBatchDto intake(rawMaterialIntakeRequest)


### Example

```typescript
import {
    RawMaterialControllerApi,
    Configuration,
    RawMaterialIntakeRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new RawMaterialControllerApi(configuration);

let rawMaterialIntakeRequest: RawMaterialIntakeRequest; //

const { status, data } = await apiInstance.intake(
    rawMaterialIntakeRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **rawMaterialIntakeRequest** | **RawMaterialIntakeRequest**|  | |


### Return type

**ApiResponseRawMaterialBatchDto**

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

# **inventory**
> ApiResponseListInventoryStockSnapshot inventory()


### Example

```typescript
import {
    RawMaterialControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new RawMaterialControllerApi(configuration);

const { status, data } = await apiInstance.inventory();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListInventoryStockSnapshot**

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

# **listRawMaterials**
> ApiResponseListRawMaterialDto listRawMaterials()


### Example

```typescript
import {
    RawMaterialControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new RawMaterialControllerApi(configuration);

const { status, data } = await apiInstance.listRawMaterials();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListRawMaterialDto**

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

# **lowStock**
> ApiResponseListInventoryStockSnapshot lowStock()


### Example

```typescript
import {
    RawMaterialControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new RawMaterialControllerApi(configuration);

const { status, data } = await apiInstance.lowStock();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListInventoryStockSnapshot**

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

# **stockSummary**
> ApiResponseStockSummaryDto stockSummary()


### Example

```typescript
import {
    RawMaterialControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new RawMaterialControllerApi(configuration);

const { status, data } = await apiInstance.stockSummary();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseStockSummaryDto**

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

# **updateRawMaterial**
> ApiResponseRawMaterialDto updateRawMaterial(rawMaterialRequest)


### Example

```typescript
import {
    RawMaterialControllerApi,
    Configuration,
    RawMaterialRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new RawMaterialControllerApi(configuration);

let id: number; // (default to undefined)
let rawMaterialRequest: RawMaterialRequest; //

const { status, data } = await apiInstance.updateRawMaterial(
    id,
    rawMaterialRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **rawMaterialRequest** | **RawMaterialRequest**|  | |
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseRawMaterialDto**

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

