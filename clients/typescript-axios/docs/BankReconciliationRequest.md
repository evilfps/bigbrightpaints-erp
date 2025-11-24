# BankReconciliationRequest


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**bankAccountId** | **number** |  | [default to undefined]
**statementDate** | **string** |  | [default to undefined]
**statementEndingBalance** | **number** |  | [default to undefined]
**startDate** | **string** |  | [optional] [default to undefined]
**endDate** | **string** |  | [optional] [default to undefined]
**clearedReferences** | **Array&lt;string&gt;** |  | [optional] [default to undefined]
**accountingPeriodId** | **number** |  | [optional] [default to undefined]
**markAsComplete** | **boolean** |  | [optional] [default to undefined]
**note** | **string** |  | [optional] [default to undefined]

## Example

```typescript
import { BankReconciliationRequest } from 'bbp-erp-api-client';

const instance: BankReconciliationRequest = {
    bankAccountId,
    statementDate,
    statementEndingBalance,
    startDate,
    endDate,
    clearedReferences,
    accountingPeriodId,
    markAsComplete,
    note,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
