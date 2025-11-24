# CatalogImportResponse


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**rowsProcessed** | **number** |  | [optional] [default to undefined]
**brandsCreated** | **number** |  | [optional] [default to undefined]
**productsCreated** | **number** |  | [optional] [default to undefined]
**productsUpdated** | **number** |  | [optional] [default to undefined]
**rawMaterialsSeeded** | **number** |  | [optional] [default to undefined]
**errors** | [**Array&lt;ImportError&gt;**](ImportError.md) |  | [optional] [default to undefined]

## Example

```typescript
import { CatalogImportResponse } from 'bbp-erp-api-client';

const instance: CatalogImportResponse = {
    rowsProcessed,
    brandsCreated,
    productsCreated,
    productsUpdated,
    rawMaterialsSeeded,
    errors,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
