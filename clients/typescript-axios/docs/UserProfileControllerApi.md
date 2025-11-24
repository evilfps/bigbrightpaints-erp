# UserProfileControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**profile**](#profile) | **GET** /api/v1/auth/profile | |
|[**update1**](#update1) | **PUT** /api/v1/auth/profile | |

# **profile**
> ApiResponseProfileResponse profile()


### Example

```typescript
import {
    UserProfileControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new UserProfileControllerApi(configuration);

const { status, data } = await apiInstance.profile();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseProfileResponse**

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

# **update1**
> ApiResponseProfileResponse update1(updateProfileRequest)


### Example

```typescript
import {
    UserProfileControllerApi,
    Configuration,
    UpdateProfileRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new UserProfileControllerApi(configuration);

let updateProfileRequest: UpdateProfileRequest; //

const { status, data } = await apiInstance.update1(
    updateProfileRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **updateProfileRequest** | **UpdateProfileRequest**|  | |


### Return type

**ApiResponseProfileResponse**

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

