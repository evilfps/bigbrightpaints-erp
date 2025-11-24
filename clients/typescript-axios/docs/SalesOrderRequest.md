# SalesOrderRequest


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**dealerId** | **number** |  | [optional] [default to undefined]
**totalAmount** | **number** |  | [default to undefined]
**currency** | **string** |  | [optional] [default to undefined]
**notes** | **string** |  | [optional] [default to undefined]
**items** | [**Array&lt;SalesOrderItemRequest&gt;**](SalesOrderItemRequest.md) |  | [default to undefined]
**gstTreatment** | **string** |  | [optional] [default to undefined]
**gstRate** | **number** |  | [optional] [default to undefined]
**gstInclusive** | **boolean** |  | [optional] [default to undefined]
**idempotencyKey** | **string** |  | [optional] [default to undefined]

## Example

```typescript
import { SalesOrderRequest } from 'bbp-erp-api-client';

const instance: SalesOrderRequest = {
    dealerId,
    totalAmount,
    currency,
    notes,
    items,
    gstTreatment,
    gstRate,
    gstInclusive,
    idempotencyKey,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
