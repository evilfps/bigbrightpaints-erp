# InventoryRevaluationRequest


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**inventoryAccountId** | **number** |  | [default to undefined]
**revaluationAccountId** | **number** |  | [default to undefined]
**deltaAmount** | **number** |  | [default to undefined]
**memo** | **string** |  | [optional] [default to undefined]
**entryDate** | **string** |  | [optional] [default to undefined]
**referenceNumber** | **string** |  | [optional] [default to undefined]
**idempotencyKey** | **string** |  | [optional] [default to undefined]
**adminOverride** | **boolean** |  | [optional] [default to undefined]

## Example

```typescript
import { InventoryRevaluationRequest } from 'bbp-erp-api-client';

const instance: InventoryRevaluationRequest = {
    inventoryAccountId,
    revaluationAccountId,
    deltaAmount,
    memo,
    entryDate,
    referenceNumber,
    idempotencyKey,
    adminOverride,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
