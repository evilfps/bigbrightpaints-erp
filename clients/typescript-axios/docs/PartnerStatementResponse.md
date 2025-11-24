# PartnerStatementResponse


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**partnerId** | **number** |  | [optional] [default to undefined]
**partnerName** | **string** |  | [optional] [default to undefined]
**fromDate** | **string** |  | [optional] [default to undefined]
**toDate** | **string** |  | [optional] [default to undefined]
**openingBalance** | **number** |  | [optional] [default to undefined]
**closingBalance** | **number** |  | [optional] [default to undefined]
**transactions** | [**Array&lt;StatementTransactionDto&gt;**](StatementTransactionDto.md) |  | [optional] [default to undefined]

## Example

```typescript
import { PartnerStatementResponse } from 'bbp-erp-api-client';

const instance: PartnerStatementResponse = {
    partnerId,
    partnerName,
    fromDate,
    toDate,
    openingBalance,
    closingBalance,
    transactions,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
