# MonthEndChecklistDto


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**period** | [**AccountingPeriodDto**](AccountingPeriodDto.md) |  | [optional] [default to undefined]
**items** | [**Array&lt;MonthEndChecklistItemDto&gt;**](MonthEndChecklistItemDto.md) |  | [optional] [default to undefined]
**readyToClose** | **boolean** |  | [optional] [default to undefined]

## Example

```typescript
import { MonthEndChecklistDto } from 'bbp-erp-api-client';

const instance: MonthEndChecklistDto = {
    period,
    items,
    readyToClose,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
