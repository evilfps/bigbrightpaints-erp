# InvoiceDto


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **number** |  | [optional] [default to undefined]
**publicId** | **string** |  | [optional] [default to undefined]
**invoiceNumber** | **string** |  | [optional] [default to undefined]
**status** | **string** |  | [optional] [default to undefined]
**subtotal** | **number** |  | [optional] [default to undefined]
**taxTotal** | **number** |  | [optional] [default to undefined]
**totalAmount** | **number** |  | [optional] [default to undefined]
**outstandingAmount** | **number** |  | [optional] [default to undefined]
**currency** | **string** |  | [optional] [default to undefined]
**issueDate** | **string** |  | [optional] [default to undefined]
**dueDate** | **string** |  | [optional] [default to undefined]
**dealerId** | **number** |  | [optional] [default to undefined]
**dealerName** | **string** |  | [optional] [default to undefined]
**salesOrderId** | **number** |  | [optional] [default to undefined]
**journalEntryId** | **number** |  | [optional] [default to undefined]
**createdAt** | **string** |  | [optional] [default to undefined]
**lines** | [**Array&lt;InvoiceLineDto&gt;**](InvoiceLineDto.md) |  | [optional] [default to undefined]

## Example

```typescript
import { InvoiceDto } from 'bbp-erp-api-client';

const instance: InvoiceDto = {
    id,
    publicId,
    invoiceNumber,
    status,
    subtotal,
    taxTotal,
    totalAmount,
    outstandingAmount,
    currency,
    issueDate,
    dueDate,
    dealerId,
    dealerName,
    salesOrderId,
    journalEntryId,
    createdAt,
    lines,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
