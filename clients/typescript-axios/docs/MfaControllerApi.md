# MfaControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**activate**](#activate) | **POST** /api/v1/auth/mfa/activate | |
|[**disable**](#disable) | **POST** /api/v1/auth/mfa/disable | |
|[**setup**](#setup) | **POST** /api/v1/auth/mfa/setup | |

# **activate**
> ApiResponseMfaStatusResponse activate(mfaActivateRequest)


### Example

```typescript
import {
    MfaControllerApi,
    Configuration,
    MfaActivateRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new MfaControllerApi(configuration);

let mfaActivateRequest: MfaActivateRequest; //

const { status, data } = await apiInstance.activate(
    mfaActivateRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **mfaActivateRequest** | **MfaActivateRequest**|  | |


### Return type

**ApiResponseMfaStatusResponse**

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

# **disable**
> ApiResponseMfaStatusResponse disable(mfaDisableRequest)


### Example

```typescript
import {
    MfaControllerApi,
    Configuration,
    MfaDisableRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new MfaControllerApi(configuration);

let mfaDisableRequest: MfaDisableRequest; //

const { status, data } = await apiInstance.disable(
    mfaDisableRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **mfaDisableRequest** | **MfaDisableRequest**|  | |


### Return type

**ApiResponseMfaStatusResponse**

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

# **setup**
> ApiResponseMfaSetupResponse setup()


### Example

```typescript
import {
    MfaControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new MfaControllerApi(configuration);

const { status, data } = await apiInstance.setup();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseMfaSetupResponse**

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

