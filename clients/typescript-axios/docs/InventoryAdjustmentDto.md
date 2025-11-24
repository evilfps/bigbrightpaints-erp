# InventoryAdjustmentDto


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **number** |  | [optional] [default to undefined]
**publicId** | **string** |  | [optional] [default to undefined]
**referenceNumber** | **string** |  | [optional] [default to undefined]
**adjustmentDate** | **string** |  | [optional] [default to undefined]
**type** | **string** |  | [optional] [default to undefined]
**status** | **string** |  | [optional] [default to undefined]
**reason** | **string** |  | [optional] [default to undefined]
**totalAmount** | **number** |  | [optional] [default to undefined]
**journalEntryId** | **number** |  | [optional] [default to undefined]
**lines** | [**Array&lt;InventoryAdjustmentLineDto&gt;**](InventoryAdjustmentLineDto.md) |  | [optional] [default to undefined]

## Example

```typescript
import { InventoryAdjustmentDto } from 'bbp-erp-api-client';

const instance: InventoryAdjustmentDto = {
    id,
    publicId,
    referenceNumber,
    adjustmentDate,
    type,
    status,
    reason,
    totalAmount,
    journalEntryId,
    lines,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
