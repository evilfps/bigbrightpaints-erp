# HrControllerApi

All URIs are relative to *http://localhost:50965*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**createEmployee**](#createemployee) | **POST** /api/v1/hr/employees | |
|[**createLeaveRequest**](#createleaverequest) | **POST** /api/v1/hr/leave-requests | |
|[**createPayrollRun**](#createpayrollrun) | **POST** /api/v1/hr/payroll-runs | |
|[**deleteEmployee**](#deleteemployee) | **DELETE** /api/v1/hr/employees/{id} | |
|[**employees**](#employees) | **GET** /api/v1/hr/employees | |
|[**leaveRequests**](#leaverequests) | **GET** /api/v1/hr/leave-requests | |
|[**payrollRuns**](#payrollruns) | **GET** /api/v1/hr/payroll-runs | |
|[**updateEmployee**](#updateemployee) | **PUT** /api/v1/hr/employees/{id} | |
|[**updateLeaveStatus**](#updateleavestatus) | **PATCH** /api/v1/hr/leave-requests/{id}/status | |

# **createEmployee**
> ApiResponseEmployeeDto createEmployee(employeeRequest)


### Example

```typescript
import {
    HrControllerApi,
    Configuration,
    EmployeeRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new HrControllerApi(configuration);

let employeeRequest: EmployeeRequest; //

const { status, data } = await apiInstance.createEmployee(
    employeeRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **employeeRequest** | **EmployeeRequest**|  | |


### Return type

**ApiResponseEmployeeDto**

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

# **createLeaveRequest**
> ApiResponseLeaveRequestDto createLeaveRequest(leaveRequestRequest)


### Example

```typescript
import {
    HrControllerApi,
    Configuration,
    LeaveRequestRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new HrControllerApi(configuration);

let leaveRequestRequest: LeaveRequestRequest; //

const { status, data } = await apiInstance.createLeaveRequest(
    leaveRequestRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **leaveRequestRequest** | **LeaveRequestRequest**|  | |


### Return type

**ApiResponseLeaveRequestDto**

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

# **createPayrollRun**
> ApiResponsePayrollRunDto createPayrollRun(payrollRunRequest)


### Example

```typescript
import {
    HrControllerApi,
    Configuration,
    PayrollRunRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new HrControllerApi(configuration);

let payrollRunRequest: PayrollRunRequest; //

const { status, data } = await apiInstance.createPayrollRun(
    payrollRunRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **payrollRunRequest** | **PayrollRunRequest**|  | |


### Return type

**ApiResponsePayrollRunDto**

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

# **deleteEmployee**
> deleteEmployee()


### Example

```typescript
import {
    HrControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new HrControllerApi(configuration);

let id: number; // (default to undefined)

const { status, data } = await apiInstance.deleteEmployee(
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

# **employees**
> ApiResponseListEmployeeDto employees()


### Example

```typescript
import {
    HrControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new HrControllerApi(configuration);

const { status, data } = await apiInstance.employees();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListEmployeeDto**

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

# **leaveRequests**
> ApiResponseListLeaveRequestDto leaveRequests()


### Example

```typescript
import {
    HrControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new HrControllerApi(configuration);

const { status, data } = await apiInstance.leaveRequests();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListLeaveRequestDto**

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

# **payrollRuns**
> ApiResponseListPayrollRunDto payrollRuns()


### Example

```typescript
import {
    HrControllerApi,
    Configuration
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new HrControllerApi(configuration);

const { status, data } = await apiInstance.payrollRuns();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**ApiResponseListPayrollRunDto**

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

# **updateEmployee**
> ApiResponseEmployeeDto updateEmployee(employeeRequest)


### Example

```typescript
import {
    HrControllerApi,
    Configuration,
    EmployeeRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new HrControllerApi(configuration);

let id: number; // (default to undefined)
let employeeRequest: EmployeeRequest; //

const { status, data } = await apiInstance.updateEmployee(
    id,
    employeeRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **employeeRequest** | **EmployeeRequest**|  | |
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseEmployeeDto**

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

# **updateLeaveStatus**
> ApiResponseLeaveRequestDto updateLeaveStatus(statusRequest)


### Example

```typescript
import {
    HrControllerApi,
    Configuration,
    StatusRequest
} from 'bbp-erp-api-client';

const configuration = new Configuration();
const apiInstance = new HrControllerApi(configuration);

let id: number; // (default to undefined)
let statusRequest: StatusRequest; //

const { status, data } = await apiInstance.updateLeaveStatus(
    id,
    statusRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **statusRequest** | **StatusRequest**|  | |
| **id** | [**number**] |  | defaults to undefined|


### Return type

**ApiResponseLeaveRequestDto**

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

