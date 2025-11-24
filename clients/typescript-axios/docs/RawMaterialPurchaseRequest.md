# RawMaterialPurchaseRequest


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**supplierId** | **number** |  | [default to undefined]
**invoiceNumber** | **string** |  | [default to undefined]
**invoiceDate** | **string** |  | [default to undefined]
**memo** | **string** |  | [optional] [default to undefined]
**lines** | [**Array&lt;RawMaterialPurchaseLineRequest&gt;**](RawMaterialPurchaseLineRequest.md) |  | [default to undefined]

## Example

```typescript
import { RawMaterialPurchaseRequest } from 'bbp-erp-api-client';

const instance: RawMaterialPurchaseRequest = {
    supplierId,
    invoiceNumber,
    invoiceDate,
    memo,
    lines,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
