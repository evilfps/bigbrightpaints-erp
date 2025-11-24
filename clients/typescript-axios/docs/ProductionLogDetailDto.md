# ProductionLogDetailDto


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **number** |  | [optional] [default to undefined]
**publicId** | **string** |  | [optional] [default to undefined]
**productionCode** | **string** |  | [optional] [default to undefined]
**producedAt** | **string** |  | [optional] [default to undefined]
**brandName** | **string** |  | [optional] [default to undefined]
**productName** | **string** |  | [optional] [default to undefined]
**skuCode** | **string** |  | [optional] [default to undefined]
**batchColour** | **string** |  | [optional] [default to undefined]
**batchSize** | **number** |  | [optional] [default to undefined]
**unitOfMeasure** | **string** |  | [optional] [default to undefined]
**mixedQuantity** | **number** |  | [optional] [default to undefined]
**totalPackedQuantity** | **number** |  | [optional] [default to undefined]
**wastageQuantity** | **number** |  | [optional] [default to undefined]
**status** | **string** |  | [optional] [default to undefined]
**materialCostTotal** | **number** |  | [optional] [default to undefined]
**laborCostTotal** | **number** |  | [optional] [default to undefined]
**overheadCostTotal** | **number** |  | [optional] [default to undefined]
**unitCost** | **number** |  | [optional] [default to undefined]
**salesOrderId** | **number** |  | [optional] [default to undefined]
**salesOrderNumber** | **string** |  | [optional] [default to undefined]
**notes** | **string** |  | [optional] [default to undefined]
**createdBy** | **string** |  | [optional] [default to undefined]
**materials** | [**Array&lt;ProductionLogMaterialDto&gt;**](ProductionLogMaterialDto.md) |  | [optional] [default to undefined]

## Example

```typescript
import { ProductionLogDetailDto } from 'bbp-erp-api-client';

const instance: ProductionLogDetailDto = {
    id,
    publicId,
    productionCode,
    producedAt,
    brandName,
    productName,
    skuCode,
    batchColour,
    batchSize,
    unitOfMeasure,
    mixedQuantity,
    totalPackedQuantity,
    wastageQuantity,
    status,
    materialCostTotal,
    laborCostTotal,
    overheadCostTotal,
    unitCost,
    salesOrderId,
    salesOrderNumber,
    notes,
    createdBy,
    materials,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
