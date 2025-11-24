# SalesOrderDto


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **number** |  | [optional] [default to undefined]
**publicId** | **string** |  | [optional] [default to undefined]
**orderNumber** | **string** |  | [optional] [default to undefined]
**status** | **string** |  | [optional] [default to undefined]
**totalAmount** | **number** |  | [optional] [default to undefined]
**subtotalAmount** | **number** |  | [optional] [default to undefined]
**gstTotal** | **number** |  | [optional] [default to undefined]
**gstRate** | **number** |  | [optional] [default to undefined]
**gstTreatment** | **string** |  | [optional] [default to undefined]
**gstRoundingAdjustment** | **number** |  | [optional] [default to undefined]
**currency** | **string** |  | [optional] [default to undefined]
**dealerName** | **string** |  | [optional] [default to undefined]
**traceId** | **string** |  | [optional] [default to undefined]
**createdAt** | **string** |  | [optional] [default to undefined]
**items** | [**Array&lt;SalesOrderItemDto&gt;**](SalesOrderItemDto.md) |  | [optional] [default to undefined]

## Example

```typescript
import { SalesOrderDto } from 'bbp-erp-api-client';

const instance: SalesOrderDto = {
    id,
    publicId,
    orderNumber,
    status,
    totalAmount,
    subtotalAmount,
    gstTotal,
    gstRate,
    gstTreatment,
    gstRoundingAdjustment,
    currency,
    dealerName,
    traceId,
    createdAt,
    items,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
