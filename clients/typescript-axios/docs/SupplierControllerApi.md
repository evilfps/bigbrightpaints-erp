# SupplierControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**createSupplier**](#createsupplier) | **POST** /api/v1/suppliers | |
|[**getSupplier**](#getsupplier) | **GET** /api/v1/suppliers/{id} | |
|[**listSuppliers**](#listsuppliers) | **GET** /api/v1/suppliers | |
|[**updateSupplier**](#updatesupplier) | **PUT** /api/v1/suppliers/{id} | |

# **createSupplier**
> ApiResponseSupplierResponse createSupplier(supplierRequest)


### Example

```typescript
import {
    SupplierControllerApi,
    Configuration,
    SupplierRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SupplierControllerApi(configuration);

let supplierRequest: SupplierRequest; //

const { status, data } = await apiInstance.createSupplier(
    supplierRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **supplierRequest** | **SupplierRequest**|  | |


### Return type

**ApiResponseSupplierResponse**

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

# **getSupplier**
> ApiResponseSupplierResponse getSupplier()


### Example

```typescript
import {
    SupplierControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SupplierControllerApi(configuration);

let id: number; // (default to undefined)

const { status, data } = await apiInstance.getSupplier(
    id
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseSupplierResponse**

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

# **listSuppliers**
> ApiResponseListSupplierResponse listSuppliers()


### Example

```typescript
import {
    SupplierControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SupplierControllerApi(configuration);

const { status, data } = await apiInstance.listSuppliers();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListSupplierResponse**

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

# **updateSupplier**
> ApiResponseSupplierResponse updateSupplier(supplierRequest)


### Example

```typescript
import {
    SupplierControllerApi,
    Configuration,
    SupplierRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new SupplierControllerApi(configuration);

let id: number; // (default to undefined)
let supplierRequest: SupplierRequest; //

const { status, data } = await apiInstance.updateSupplier(
    id,
    supplierRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **supplierRequest** | **SupplierRequest**|  | |
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseSupplierResponse**

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

