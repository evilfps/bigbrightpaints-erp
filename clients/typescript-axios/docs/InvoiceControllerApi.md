# InvoiceControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**dealerInvoices**](#dealerinvoices) | **GET** /api/v1/invoices/dealers/{dealerId} | |
|[**getInvoice**](#getinvoice) | **GET** /api/v1/invoices/{id} | |
|[**listInvoices**](#listinvoices) | **GET** /api/v1/invoices | |

# **dealerInvoices**
> ApiResponseListInvoiceDto dealerInvoices()


### Example

```typescript
import {
    InvoiceControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new InvoiceControllerApi(configuration);

let dealerId: number; // (default to undefined)

const { status, data } = await apiInstance.dealerInvoices(
    dealerId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **dealerId** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseListInvoiceDto**

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

# **getInvoice**
> ApiResponseInvoiceDto getInvoice()


### Example

```typescript
import {
    InvoiceControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new InvoiceControllerApi(configuration);

let id: number; // (default to undefined)

const { status, data } = await apiInstance.getInvoice(
    id
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseInvoiceDto**

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

# **listInvoices**
> ApiResponseListInvoiceDto listInvoices()


### Example

```typescript
import {
    InvoiceControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new InvoiceControllerApi(configuration);

const { status, data } = await apiInstance.listInvoices();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListInvoiceDto**

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

