# ProductionLogRequest


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**brandId** | **number** |  | [default to undefined]
**productId** | **number** |  | [default to undefined]
**batchColour** | **string** |  | [optional] [default to undefined]
**batchSize** | **number** |  | [default to undefined]
**unitOfMeasure** | **string** |  | [optional] [default to undefined]
**mixedQuantity** | **number** |  | [default to undefined]
**producedAt** | **string** |  | [optional] [default to undefined]
**notes** | **string** |  | [optional] [default to undefined]
**createdBy** | **string** |  | [optional] [default to undefined]
**addToFinishedGoods** | **boolean** |  | [optional] [default to undefined]
**salesOrderId** | **number** |  | [optional] [default to undefined]
**laborCost** | **number** |  | [optional] [default to undefined]
**overheadCost** | **number** |  | [optional] [default to undefined]
**materials** | [**Array&lt;MaterialUsageRequest&gt;**](MaterialUsageRequest.md) |  | [default to undefined]

## Example

```typescript
import { ProductionLogRequest } from 'bbp-erp-api-client';

const instance: ProductionLogRequest = {
    brandId,
    productId,
    batchColour,
    batchSize,
    unitOfMeasure,
    mixedQuantity,
    producedAt,
    notes,
    createdBy,
    addToFinishedGoods,
    salesOrderId,
    laborCost,
    overheadCost,
    materials,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
