# AccountingCatalogControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**createProduct**](#createproduct) | **POST** /api/v1/accounting/catalog/products | |
|[**importCatalog**](#importcatalog) | **POST** /api/v1/accounting/catalog/import | |
|[**listProducts**](#listproducts) | **GET** /api/v1/accounting/catalog/products | |
|[**updateProduct**](#updateproduct) | **PUT** /api/v1/accounting/catalog/products/{id} | |

# **createProduct**
> ApiResponseProductionProductDto createProduct(productCreateRequest)


### Example

```typescript
import {
    AccountingCatalogControllerApi,
    Configuration,
    ProductCreateRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new AccountingCatalogControllerApi(configuration);

let productCreateRequest: ProductCreateRequest; //

const { status, data } = await apiInstance.createProduct(
    productCreateRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **productCreateRequest** | **ProductCreateRequest**|  | |


### Return type

**ApiResponseProductionProductDto**

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

# **importCatalog**
> ApiResponseCatalogImportResponse importCatalog()


### Example

```typescript
import {
    AccountingCatalogControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new AccountingCatalogControllerApi(configuration);

let file: File; // (default to undefined)

const { status, data } = await apiInstance.importCatalog(
    file
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **file** | [**File**] |  | defaults to undefined|


### Return type

**ApiResponseCatalogImportResponse**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: multipart/form-data
 - **Accept**: */*


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | OK |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **listProducts**
> ApiResponseListProductionProductDto listProducts()


### Example

```typescript
import {
    AccountingCatalogControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new AccountingCatalogControllerApi(configuration);

const { status, data } = await apiInstance.listProducts();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListProductionProductDto**

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

# **updateProduct**
> ApiResponseProductionProductDto updateProduct(productUpdateRequest)


### Example

```typescript
import {
    AccountingCatalogControllerApi,
    Configuration,
    ProductUpdateRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new AccountingCatalogControllerApi(configuration);

let id: number; // (default to undefined)
let productUpdateRequest: ProductUpdateRequest; //

const { status, data } = await apiInstance.updateProduct(
    id,
    productUpdateRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **productUpdateRequest** | **ProductUpdateRequest**|  | |
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseProductionProductDto**

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

