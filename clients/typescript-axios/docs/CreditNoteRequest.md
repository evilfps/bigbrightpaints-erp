# CreditNoteRequest


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**invoiceId** | **number** |  | [default to undefined]
**entryDate** | **string** |  | [optional] [default to undefined]
**referenceNumber** | **string** |  | [optional] [default to undefined]
**memo** | **string** |  | [optional] [default to undefined]
**idempotencyKey** | **string** |  | [optional] [default to undefined]
**adminOverride** | **boolean** |  | [optional] [default to undefined]

## Example

```typescript
import { CreditNoteRequest } from 'bbp-erp-api-client';

const instance: CreditNoteRequest = {
    invoiceId,
    entryDate,
    referenceNumber,
    memo,
    idempotencyKey,
    adminOverride,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
