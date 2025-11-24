# ProductionCatalogControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**listBrandProducts**](#listbrandproducts) | **GET** /api/v1/production/brands/{brandId}/products | |
|[**listBrands**](#listbrands) | **GET** /api/v1/production/brands | |

# **listBrandProducts**
> ApiResponseListProductionProductDto listBrandProducts()


### Example

```typescript
import {
    ProductionCatalogControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new ProductionCatalogControllerApi(configuration);

let brandId: number; // (default to undefined)

const { status, data } = await apiInstance.listBrandProducts(
    brandId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **brandId** | [**number**] |  | defaults to undefined|


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

# **listBrands**
> ApiResponseListProductionBrandDto listBrands()


### Example

```typescript
import {
    ProductionCatalogControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new ProductionCatalogControllerApi(configuration);

const { status, data } = await apiInstance.listBrands();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListProductionBrandDto**

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

