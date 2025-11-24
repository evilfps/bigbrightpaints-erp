# WipAdjustmentRequest


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**productionLogId** | **number** |  | [default to undefined]
**amount** | **number** |  | [default to undefined]
**wipAccountId** | **number** |  | [default to undefined]
**inventoryAccountId** | **number** |  | [default to undefined]
**direction** | **string** |  | [default to undefined]
**memo** | **string** |  | [optional] [default to undefined]
**entryDate** | **string** |  | [optional] [default to undefined]
**referenceNumber** | **string** |  | [optional] [default to undefined]
**idempotencyKey** | **string** |  | [optional] [default to undefined]
**adminOverride** | **boolean** |  | [optional] [default to undefined]

## Example

```typescript
import { WipAdjustmentRequest } from 'bbp-erp-api-client';

const instance: WipAdjustmentRequest = {
    productionLogId,
    amount,
    wipAccountId,
    inventoryAccountId,
    direction,
    memo,
    entryDate,
    referenceNumber,
    idempotencyKey,
    adminOverride,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
