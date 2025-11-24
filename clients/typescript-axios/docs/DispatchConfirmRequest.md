# DispatchConfirmRequest


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**packingSlipId** | **number** |  | [optional] [default to undefined]
**orderId** | **number** |  | [optional] [default to undefined]
**lines** | [**Array&lt;DispatchLine&gt;**](DispatchLine.md) |  | [optional] [default to undefined]
**adminOverrideCreditLimit** | **boolean** |  | [optional] [default to undefined]

## Example

```typescript
import { DispatchConfirmRequest } from 'bbp-erp-api-client';

const instance: DispatchConfirmRequest = {
    packingSlipId,
    orderId,
    lines,
    adminOverrideCreditLimit,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
