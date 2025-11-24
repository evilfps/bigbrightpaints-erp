# DemoControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**ping**](#ping) | **GET** /api/v1/demo/ping | |

# **ping**
> ApiResponseString ping()


### Example

```typescript
import {
    DemoControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new DemoControllerApi(configuration);

const { status, data } = await apiInstance.ping();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseString**

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

