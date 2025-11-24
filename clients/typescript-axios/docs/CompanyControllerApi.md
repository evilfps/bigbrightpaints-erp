# CompanyControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**_delete**](#_delete) | **DELETE** /api/v1/companies/{id} | |
|[**create1**](#create1) | **POST** /api/v1/companies | |
|[**list1**](#list1) | **GET** /api/v1/companies | |
|[**update**](#update) | **PUT** /api/v1/companies/{id} | |

# **_delete**
> _delete()


### Example

```typescript
import {
    CompanyControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new CompanyControllerApi(configuration);

let id: number; // (default to undefined)

const { status, data } = await apiInstance._delete(
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

# **create1**
> ApiResponseCompanyDto create1(companyRequest)


### Example

```typescript
import {
    CompanyControllerApi,
    Configuration,
    CompanyRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new CompanyControllerApi(configuration);

let companyRequest: CompanyRequest; //

const { status, data } = await apiInstance.create1(
    companyRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **companyRequest** | **CompanyRequest**|  | |


### Return type

**ApiResponseCompanyDto**

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

# **list1**
> ApiResponseListCompanyDto list1()


### Example

```typescript
import {
    CompanyControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new CompanyControllerApi(configuration);

const { status, data } = await apiInstance.list1();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListCompanyDto**

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

# **update**
> ApiResponseCompanyDto update(companyRequest)


### Example

```typescript
import {
    CompanyControllerApi,
    Configuration,
    CompanyRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new CompanyControllerApi(configuration);

let id: number; // (default to undefined)
let companyRequest: CompanyRequest; //

const { status, data } = await apiInstance.update(
    id,
    companyRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **companyRequest** | **CompanyRequest**|  | |
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseCompanyDto**

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

