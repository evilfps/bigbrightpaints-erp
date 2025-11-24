# AdminUserControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**create2**](#create2) | **POST** /api/v1/admin/users | |
|[**list2**](#list2) | **GET** /api/v1/admin/users | |
|[**suspend**](#suspend) | **PATCH** /api/v1/admin/users/{id}/suspend | |
|[**unsuspend**](#unsuspend) | **PATCH** /api/v1/admin/users/{id}/unsuspend | |
|[**update2**](#update2) | **PUT** /api/v1/admin/users/{id} | |

# **create2**
> ApiResponseUserDto create2(createUserRequest)


### Example

```typescript
import {
    AdminUserControllerApi,
    Configuration,
    CreateUserRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new AdminUserControllerApi(configuration);

let createUserRequest: CreateUserRequest; //

const { status, data } = await apiInstance.create2(
    createUserRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **createUserRequest** | **CreateUserRequest**|  | |


### Return type

**ApiResponseUserDto**

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

# **list2**
> ApiResponseListUserDto list2()


### Example

```typescript
import {
    AdminUserControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new AdminUserControllerApi(configuration);

const { status, data } = await apiInstance.list2();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListUserDto**

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

# **suspend**
> suspend()


### Example

```typescript
import {
    AdminUserControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new AdminUserControllerApi(configuration);

let id: number; // (default to undefined)

const { status, data } = await apiInstance.suspend(
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

# **unsuspend**
> unsuspend()


### Example

```typescript
import {
    AdminUserControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new AdminUserControllerApi(configuration);

let id: number; // (default to undefined)

const { status, data } = await apiInstance.unsuspend(
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

# **update2**
> ApiResponseUserDto update2(updateUserRequest)


### Example

```typescript
import {
    AdminUserControllerApi,
    Configuration,
    UpdateUserRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new AdminUserControllerApi(configuration);

let id: number; // (default to undefined)
let updateUserRequest: UpdateUserRequest; //

const { status, data } = await apiInstance.update2(
    id,
    updateUserRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **updateUserRequest** | **UpdateUserRequest**|  | |
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseUserDto**

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

