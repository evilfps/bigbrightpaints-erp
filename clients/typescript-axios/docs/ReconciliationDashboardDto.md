# ReconciliationDashboardDto


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**ledgerInventoryBalance** | **number** |  | [optional] [default to undefined]
**physicalInventoryValue** | **number** |  | [optional] [default to undefined]
**inventoryVariance** | **number** |  | [optional] [default to undefined]
**bankLedgerBalance** | **number** |  | [optional] [default to undefined]
**bankStatementBalance** | **number** |  | [optional] [default to undefined]
**bankVariance** | **number** |  | [optional] [default to undefined]
**inventoryBalanced** | **boolean** |  | [optional] [default to undefined]
**bankBalanced** | **boolean** |  | [optional] [default to undefined]
**balanceWarnings** | [**Array&lt;BalanceWarningDto&gt;**](BalanceWarningDto.md) |  | [optional] [default to undefined]

## Example

```typescript
import { ReconciliationDashboardDto } from 'bbp-erp-api-client';

const instance: ReconciliationDashboardDto = {
    ledgerInventoryBalance,
    physicalInventoryValue,
    inventoryVariance,
    bankLedgerBalance,
    bankStatementBalance,
    bankVariance,
    inventoryBalanced,
    bankBalanced,
    balanceWarnings,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
