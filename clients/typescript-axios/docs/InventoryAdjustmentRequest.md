# InventoryAdjustmentRequest


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**adjustmentDate** | **string** |  | [optional] [default to undefined]
**type** | **string** |  | [default to undefined]
**adjustmentAccountId** | **number** |  | [default to undefined]
**reason** | **string** |  | [optional] [default to undefined]
**adminOverride** | **boolean** |  | [optional] [default to undefined]
**lines** | [**Array&lt;LineRequest&gt;**](LineRequest.md) |  | [default to undefined]

## Example

```typescript
import { InventoryAdjustmentRequest } from 'bbp-erp-api-client';

const instance: InventoryAdjustmentRequest = {
    adjustmentDate,
    type,
    adjustmentAccountId,
    reason,
    adminOverride,
    lines,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
