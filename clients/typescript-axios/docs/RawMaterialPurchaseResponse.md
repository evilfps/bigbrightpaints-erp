# RawMaterialPurchaseResponse


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **number** |  | [optional] [default to undefined]
**publicId** | **string** |  | [optional] [default to undefined]
**invoiceNumber** | **string** |  | [optional] [default to undefined]
**invoiceDate** | **string** |  | [optional] [default to undefined]
**totalAmount** | **number** |  | [optional] [default to undefined]
**outstandingAmount** | **number** |  | [optional] [default to undefined]
**status** | **string** |  | [optional] [default to undefined]
**memo** | **string** |  | [optional] [default to undefined]
**supplierId** | **number** |  | [optional] [default to undefined]
**supplierCode** | **string** |  | [optional] [default to undefined]
**supplierName** | **string** |  | [optional] [default to undefined]
**journalEntryId** | **number** |  | [optional] [default to undefined]
**createdAt** | **string** |  | [optional] [default to undefined]
**lines** | [**Array&lt;RawMaterialPurchaseLineResponse&gt;**](RawMaterialPurchaseLineResponse.md) |  | [optional] [default to undefined]

## Example

```typescript
import { RawMaterialPurchaseResponse } from 'bbp-erp-api-client';

const instance: RawMaterialPurchaseResponse = {
    id,
    publicId,
    invoiceNumber,
    invoiceDate,
    totalAmount,
    outstandingAmount,
    status,
    memo,
    supplierId,
    supplierCode,
    supplierName,
    journalEntryId,
    createdAt,
    lines,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
