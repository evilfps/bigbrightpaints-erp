# BankReconciliationSummaryDto


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**accountId** | **number** |  | [optional] [default to undefined]
**accountCode** | **string** |  | [optional] [default to undefined]
**accountName** | **string** |  | [optional] [default to undefined]
**statementDate** | **string** |  | [optional] [default to undefined]
**ledgerBalance** | **number** |  | [optional] [default to undefined]
**statementEndingBalance** | **number** |  | [optional] [default to undefined]
**outstandingDeposits** | **number** |  | [optional] [default to undefined]
**outstandingChecks** | **number** |  | [optional] [default to undefined]
**difference** | **number** |  | [optional] [default to undefined]
**balanced** | **boolean** |  | [optional] [default to undefined]
**unclearedDeposits** | [**Array&lt;BankReconciliationItemDto&gt;**](BankReconciliationItemDto.md) |  | [optional] [default to undefined]
**unclearedChecks** | [**Array&lt;BankReconciliationItemDto&gt;**](BankReconciliationItemDto.md) |  | [optional] [default to undefined]

## Example

```typescript
import { BankReconciliationSummaryDto } from 'bbp-erp-api-client';

const instance: BankReconciliationSummaryDto = {
    accountId,
    accountCode,
    accountName,
    statementDate,
    ledgerBalance,
    statementEndingBalance,
    outstandingDeposits,
    outstandingChecks,
    difference,
    balanced,
    unclearedDeposits,
    unclearedChecks,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
