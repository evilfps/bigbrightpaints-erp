# JournalEntryRequest


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**referenceNumber** | **string** |  | [optional] [default to undefined]
**entryDate** | **string** |  | [default to undefined]
**memo** | **string** |  | [optional] [default to undefined]
**dealerId** | **number** |  | [optional] [default to undefined]
**supplierId** | **number** |  | [optional] [default to undefined]
**adminOverride** | **boolean** |  | [optional] [default to undefined]
**lines** | [**Array&lt;JournalLineRequest&gt;**](JournalLineRequest.md) |  | [default to undefined]
**currency** | **string** |  | [optional] [default to undefined]
**fxRate** | **number** |  | [optional] [default to undefined]

## Example

```typescript
import { JournalEntryRequest } from 'bbp-erp-api-client';

const instance: JournalEntryRequest = {
    referenceNumber,
    entryDate,
    memo,
    dealerId,
    supplierId,
    adminOverride,
    lines,
    currency,
    fxRate,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
