# MultiCompanyControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**switchCompany**](#switchcompany) | **POST** /api/v1/multi-company/companies/switch | |

# **switchCompany**
> ApiResponseCompanyDto switchCompany(switchCompanyRequest)


### Example

```typescript
import {
    MultiCompanyControllerApi,
    Configuration,
    SwitchCompanyRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new MultiCompanyControllerApi(configuration);

let switchCompanyRequest: SwitchCompanyRequest; //

const { status, data } = await apiInstance.switchCompany(
    switchCompanyRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **switchCompanyRequest** | **SwitchCompanyRequest**|  | |


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

