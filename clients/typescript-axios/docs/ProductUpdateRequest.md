# ProductUpdateRequest


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**productName** | **string** |  | [optional] [default to undefined]
**category** | **string** |  | [optional] [default to undefined]
**defaultColour** | **string** |  | [optional] [default to undefined]
**sizeLabel** | **string** |  | [optional] [default to undefined]
**unitOfMeasure** | **string** |  | [optional] [default to undefined]
**basePrice** | **number** |  | [optional] [default to undefined]
**gstRate** | **number** |  | [optional] [default to undefined]
**minDiscountPercent** | **number** |  | [optional] [default to undefined]
**minSellingPrice** | **number** |  | [optional] [default to undefined]
**metadata** | **{ [key: string]: object; }** |  | [optional] [default to undefined]

## Example

```typescript
import { ProductUpdateRequest } from 'bbp-erp-api-client';

const instance: ProductUpdateRequest = {
    productName,
    category,
    defaultColour,
    sizeLabel,
    unitOfMeasure,
    basePrice,
    gstRate,
    minDiscountPercent,
    minSellingPrice,
    metadata,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
