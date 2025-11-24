# JournalEntryDto


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **number** |  | [optional] [default to undefined]
**publicId** | **string** |  | [optional] [default to undefined]
**referenceNumber** | **string** |  | [optional] [default to undefined]
**entryDate** | **string** |  | [optional] [default to undefined]
**memo** | **string** |  | [optional] [default to undefined]
**status** | **string** |  | [optional] [default to undefined]
**dealerId** | **number** |  | [optional] [default to undefined]
**dealerName** | **string** |  | [optional] [default to undefined]
**supplierId** | **number** |  | [optional] [default to undefined]
**supplierName** | **string** |  | [optional] [default to undefined]
**accountingPeriodId** | **number** |  | [optional] [default to undefined]
**accountingPeriodLabel** | **string** |  | [optional] [default to undefined]
**accountingPeriodStatus** | **string** |  | [optional] [default to undefined]
**reversalOfEntryId** | **number** |  | [optional] [default to undefined]
**reversalEntryId** | **number** |  | [optional] [default to undefined]
**correctionType** | **string** |  | [optional] [default to undefined]
**correctionReason** | **string** |  | [optional] [default to undefined]
**voidReason** | **string** |  | [optional] [default to undefined]
**lines** | [**Array&lt;JournalLineDto&gt;**](JournalLineDto.md) |  | [optional] [default to undefined]
**createdAt** | **string** |  | [optional] [default to undefined]
**updatedAt** | **string** |  | [optional] [default to undefined]
**postedAt** | **string** |  | [optional] [default to undefined]
**createdBy** | **string** |  | [optional] [default to undefined]
**postedBy** | **string** |  | [optional] [default to undefined]
**lastModifiedBy** | **string** |  | [optional] [default to undefined]

## Example

```typescript
import { JournalEntryDto } from 'bbp-erp-api-client';

const instance: JournalEntryDto = {
    id,
    publicId,
    referenceNumber,
    entryDate,
    memo,
    status,
    dealerId,
    dealerName,
    supplierId,
    supplierName,
    accountingPeriodId,
    accountingPeriodLabel,
    accountingPeriodStatus,
    reversalOfEntryId,
    reversalEntryId,
    correctionType,
    correctionReason,
    voidReason,
    lines,
    createdAt,
    updatedAt,
    postedAt,
    createdBy,
    postedBy,
    lastModifiedBy,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
