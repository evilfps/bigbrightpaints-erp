# SupplierSettlementRequest


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**supplierId** | **number** |  | [default to undefined]
**cashAccountId** | **number** |  | [default to undefined]
**discountAccountId** | **number** |  | [optional] [default to undefined]
**writeOffAccountId** | **number** |  | [optional] [default to undefined]
**fxGainAccountId** | **number** |  | [optional] [default to undefined]
**fxLossAccountId** | **number** |  | [optional] [default to undefined]
**settlementDate** | **string** |  | [optional] [default to undefined]
**referenceNumber** | **string** |  | [optional] [default to undefined]
**memo** | **string** |  | [optional] [default to undefined]
**idempotencyKey** | **string** |  | [optional] [default to undefined]
**adminOverride** | **boolean** |  | [optional] [default to undefined]
**allocations** | [**Array&lt;SettlementAllocationRequest&gt;**](SettlementAllocationRequest.md) |  | [default to undefined]

## Example

```typescript
import { SupplierSettlementRequest } from 'bbp-erp-api-client';

const instance: SupplierSettlementRequest = {
    supplierId,
    cashAccountId,
    discountAccountId,
    writeOffAccountId,
    fxGainAccountId,
    fxLossAccountId,
    settlementDate,
    referenceNumber,
    memo,
    idempotencyKey,
    adminOverride,
    allocations,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
