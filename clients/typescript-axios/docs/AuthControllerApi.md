# AuthControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**changePassword**](#changepassword) | **POST** /api/v1/auth/password/change | |
|[**forgotPassword**](#forgotpassword) | **POST** /api/v1/auth/password/forgot | |
|[**login**](#login) | **POST** /api/v1/auth/login | |
|[**logout**](#logout) | **POST** /api/v1/auth/logout | |
|[**me**](#me) | **GET** /api/v1/auth/me | |
|[**refresh**](#refresh) | **POST** /api/v1/auth/refresh-token | |
|[**resetPassword**](#resetpassword) | **POST** /api/v1/auth/password/reset | |

# **changePassword**
> ApiResponseString changePassword(changePasswordRequest)


### Example

```typescript
import {
    AuthControllerApi,
    Configuration,
    ChangePasswordRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new AuthControllerApi(configuration);

let changePasswordRequest: ChangePasswordRequest; //

const { status, data } = await apiInstance.changePassword(
    changePasswordRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **changePasswordRequest** | **ChangePasswordRequest**|  | |


### Return type

**ApiResponseString**

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

# **forgotPassword**
> ApiResponseString forgotPassword(forgotPasswordRequest)


### Example

```typescript
import {
    AuthControllerApi,
    Configuration,
    ForgotPasswordRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new AuthControllerApi(configuration);

let forgotPasswordRequest: ForgotPasswordRequest; //

const { status, data } = await apiInstance.forgotPassword(
    forgotPasswordRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **forgotPasswordRequest** | **ForgotPasswordRequest**|  | |


### Return type

**ApiResponseString**

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

# **login**
> AuthResponse login(loginRequest)


### Example

```typescript
import {
    AuthControllerApi,
    Configuration,
    LoginRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new AuthControllerApi(configuration);

let loginRequest: LoginRequest; //

const { status, data } = await apiInstance.login(
    loginRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **loginRequest** | **LoginRequest**|  | |


### Return type

**AuthResponse**

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

# **logout**
> logout()


### Example

```typescript
import {
    AuthControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new AuthControllerApi(configuration);

let refreshToken: string; // (optional) (default to undefined)

const { status, data } = await apiInstance.logout(
    refreshToken
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **refreshToken** | [**string**] |  | (optional) defaults to undefined|


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

# **me**
> ApiResponseMeResponse me()


### Example

```typescript
import {
    AuthControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new AuthControllerApi(configuration);

const { status, data } = await apiInstance.me();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseMeResponse**

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

# **refresh**
> AuthResponse refresh(refreshTokenRequest)


### Example

```typescript
import {
    AuthControllerApi,
    Configuration,
    RefreshTokenRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new AuthControllerApi(configuration);

let refreshTokenRequest: RefreshTokenRequest; //

const { status, data } = await apiInstance.refresh(
    refreshTokenRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **refreshTokenRequest** | **RefreshTokenRequest**|  | |


### Return type

**AuthResponse**

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

# **resetPassword**
> ApiResponseString resetPassword(resetPasswordRequest)


### Example

```typescript
import {
    AuthControllerApi,
    Configuration,
    ResetPasswordRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new AuthControllerApi(configuration);

let resetPasswordRequest: ResetPasswordRequest; //

const { status, data } = await apiInstance.resetPassword(
    resetPasswordRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **resetPasswordRequest** | **ResetPasswordRequest**|  | |


### Return type

**ApiResponseString**

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

