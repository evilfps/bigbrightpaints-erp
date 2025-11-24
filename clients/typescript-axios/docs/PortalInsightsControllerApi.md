# PortalInsightsControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**dashboard**](#dashboard) | **GET** /api/v1/portal/dashboard | |
|[**operations**](#operations) | **GET** /api/v1/portal/operations | |
|[**workforce**](#workforce) | **GET** /api/v1/portal/workforce | |

# **dashboard**
> ApiResponseDashboardInsights dashboard()


### Example

```typescript
import {
    PortalInsightsControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new PortalInsightsControllerApi(configuration);

const { status, data } = await apiInstance.dashboard();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseDashboardInsights**

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

# **operations**
> ApiResponseOperationsInsights operations()


### Example

```typescript
import {
    PortalInsightsControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new PortalInsightsControllerApi(configuration);

const { status, data } = await apiInstance.operations();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseOperationsInsights**

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

# **workforce**
> ApiResponseWorkforceInsights workforce()


### Example

```typescript
import {
    PortalInsightsControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new PortalInsightsControllerApi(configuration);

const { status, data } = await apiInstance.workforce();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseWorkforceInsights**

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

