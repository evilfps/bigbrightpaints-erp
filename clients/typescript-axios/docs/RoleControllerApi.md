# RoleControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**createRole**](#createrole) | **POST** /api/v1/admin/roles | |
|[**listRoles**](#listroles) | **GET** /api/v1/admin/roles | |

# **createRole**
> ApiResponseRoleDto createRole(createRoleRequest)


### Example

```typescript
import {
    RoleControllerApi,
    Configuration,
    CreateRoleRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new RoleControllerApi(configuration);

let createRoleRequest: CreateRoleRequest; //

const { status, data } = await apiInstance.createRole(
    createRoleRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **createRoleRequest** | **CreateRoleRequest**|  | |


### Return type

**ApiResponseRoleDto**

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

# **listRoles**
> ApiResponseListRoleDto listRoles()


### Example

```typescript
import {
    RoleControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new RoleControllerApi(configuration);

const { status, data } = await apiInstance.listRoles();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListRoleDto**

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

