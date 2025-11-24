# InventoryCountRequest


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**target** | **string** |  | [default to undefined]
**itemId** | **number** |  | [default to undefined]
**physicalQuantity** | **number** |  | [default to undefined]
**unitCost** | **number** |  | [default to undefined]
**adjustmentAccountId** | **number** |  | [default to undefined]
**countDate** | **string** |  | [optional] [default to undefined]
**accountingPeriodId** | **number** |  | [optional] [default to undefined]
**markAsComplete** | **boolean** |  | [optional] [default to undefined]
**note** | **string** |  | [optional] [default to undefined]

## Example

```typescript
import { InventoryCountRequest } from 'bbp-erp-api-client';

const instance: InventoryCountRequest = {
    target,
    itemId,
    physicalQuantity,
    unitCost,
    adjustmentAccountId,
    countDate,
    accountingPeriodId,
    markAsComplete,
    note,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
