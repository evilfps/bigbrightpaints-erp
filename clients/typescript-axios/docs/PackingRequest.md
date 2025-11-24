# PackingRequest


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**productionLogId** | **number** |  | [default to undefined]
**packedDate** | **string** |  | [optional] [default to undefined]
**packedBy** | **string** |  | [optional] [default to undefined]
**lines** | [**Array&lt;PackingLineRequest&gt;**](PackingLineRequest.md) |  | [default to undefined]

## Example

```typescript
import { PackingRequest } from 'bbp-erp-api-client';

const instance: PackingRequest = {
    productionLogId,
    packedDate,
    packedBy,
    lines,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
