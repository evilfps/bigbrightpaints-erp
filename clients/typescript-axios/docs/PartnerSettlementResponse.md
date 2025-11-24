# PartnerSettlementResponse


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**journalEntry** | [**JournalEntryDto**](JournalEntryDto.md) |  | [optional] [default to undefined]
**totalApplied** | **number** |  | [optional] [default to undefined]
**cashAmount** | **number** |  | [optional] [default to undefined]
**totalDiscount** | **number** |  | [optional] [default to undefined]
**totalWriteOff** | **number** |  | [optional] [default to undefined]
**totalFxGain** | **number** |  | [optional] [default to undefined]
**totalFxLoss** | **number** |  | [optional] [default to undefined]
**allocations** | [**Array&lt;Allocation&gt;**](Allocation.md) |  | [optional] [default to undefined]

## Example

```typescript
import { PartnerSettlementResponse } from 'bbp-erp-api-client';

const instance: PartnerSettlementResponse = {
    journalEntry,
    totalApplied,
    cashAmount,
    totalDiscount,
    totalWriteOff,
    totalFxGain,
    totalFxLoss,
    allocations,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
