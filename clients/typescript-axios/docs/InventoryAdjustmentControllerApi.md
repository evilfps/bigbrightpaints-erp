# InventoryAdjustmentControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**createAdjustment**](#createadjustment) | **POST** /api/v1/inventory/adjustments | |
|[**listAdjustments**](#listadjustments) | **GET** /api/v1/inventory/adjustments | |

# **createAdjustment**
> ApiResponseInventoryAdjustmentDto createAdjustment(inventoryAdjustmentRequest)


### Example

```typescript
import {
    InventoryAdjustmentControllerApi,
    Configuration,
    InventoryAdjustmentRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new InventoryAdjustmentControllerApi(configuration);

let inventoryAdjustmentRequest: InventoryAdjustmentRequest; //

const { status, data } = await apiInstance.createAdjustment(
    inventoryAdjustmentRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **inventoryAdjustmentRequest** | **InventoryAdjustmentRequest**|  | |


### Return type

**ApiResponseInventoryAdjustmentDto**

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

# **listAdjustments**
> ApiResponseListInventoryAdjustmentDto listAdjustments()


### Example

```typescript
import {
    InventoryAdjustmentControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new InventoryAdjustmentControllerApi(configuration);

const { status, data } = await apiInstance.listAdjustments();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListInventoryAdjustmentDto**

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

