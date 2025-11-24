# AccrualRequest


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**debitAccountId** | **number** |  | [default to undefined]
**creditAccountId** | **number** |  | [default to undefined]
**amount** | **number** |  | [default to undefined]
**entryDate** | **string** |  | [optional] [default to undefined]
**referenceNumber** | **string** |  | [optional] [default to undefined]
**memo** | **string** |  | [optional] [default to undefined]
**idempotencyKey** | **string** |  | [optional] [default to undefined]
**autoReverseDate** | **string** |  | [optional] [default to undefined]
**adminOverride** | **boolean** |  | [optional] [default to undefined]

## Example

```typescript
import { AccrualRequest } from 'bbp-erp-api-client';

const instance: AccrualRequest = {
    debitAccountId,
    creditAccountId,
    amount,
    entryDate,
    referenceNumber,
    memo,
    idempotencyKey,
    autoReverseDate,
    adminOverride,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
