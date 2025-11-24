# DispatchConfirmResponse


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**packingSlipId** | **number** |  | [optional] [default to undefined]
**salesOrderId** | **number** |  | [optional] [default to undefined]
**finalInvoiceId** | **number** |  | [optional] [default to undefined]
**arJournalEntryId** | **number** |  | [optional] [default to undefined]
**cogsPostings** | [**Array&lt;CogsPostingDto&gt;**](CogsPostingDto.md) |  | [optional] [default to undefined]
**dispatched** | **boolean** |  | [optional] [default to undefined]

## Example

```typescript
import { DispatchConfirmResponse } from 'bbp-erp-api-client';

const instance: DispatchConfirmResponse = {
    packingSlipId,
    salesOrderId,
    finalInvoiceId,
    arJournalEntryId,
    cogsPostings,
    dispatched,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
