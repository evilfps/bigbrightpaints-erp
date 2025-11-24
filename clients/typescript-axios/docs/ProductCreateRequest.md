# ProductCreateRequest


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**brandId** | **number** |  | [optional] [default to undefined]
**brandName** | **string** |  | [optional] [default to undefined]
**brandCode** | **string** |  | [optional] [default to undefined]
**productName** | **string** |  | [default to undefined]
**category** | **string** |  | [default to undefined]
**defaultColour** | **string** |  | [optional] [default to undefined]
**sizeLabel** | **string** |  | [optional] [default to undefined]
**unitOfMeasure** | **string** |  | [optional] [default to undefined]
**customSkuCode** | **string** |  | [optional] [default to undefined]
**basePrice** | **number** |  | [optional] [default to undefined]
**gstRate** | **number** |  | [optional] [default to undefined]
**minDiscountPercent** | **number** |  | [optional] [default to undefined]
**minSellingPrice** | **number** |  | [optional] [default to undefined]
**metadata** | **{ [key: string]: object; }** |  | [optional] [default to undefined]

## Example

```typescript
import { ProductCreateRequest } from 'bbp-erp-api-client';

const instance: ProductCreateRequest = {
    brandId,
    brandName,
    brandCode,
    productName,
    category,
    defaultColour,
    sizeLabel,
    unitOfMeasure,
    customSkuCode,
    basePrice,
    gstRate,
    minDiscountPercent,
    minSellingPrice,
    metadata,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
