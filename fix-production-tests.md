# Production Test Fixes Required

## Issues Found:
1. Wrong endpoint: `/api/v1/factory/production-logs` should be `/api/v1/factory/production/logs`
2. Wrong request fields for production log:
   - Should use `brandId` and `productId`, not `productCode`
   - Should use `materials` array with `rawMaterialId` and `quantity`, not `rawMaterialCode` and `quantityUsed`

3. Wrong endpoint for packing: `/api/v1/factory/packing` should be `/api/v1/factory/packing-records`
4. Wrong packing request format:
   - Use `lines` array instead of `packingLines`
   - Use `packagingSize` instead of `containerSize`/`unitLabel`
   - Don't include `wastage` and `wastageReason` in request (calculated automatically)

## Correct Request Formats:

### Production Log:
```json
{
  "brandId": 1,
  "productId": 5,
  "batchSize": 100.00,
  "mixedQuantity": 100.00,
  "materials": [
    {
      "rawMaterialId": 10,
      "quantity": 50.00
    }
  ]
}
```

### Packing:
```json
{
  "productionLogId": 123,
  "lines": [
    {
      "packagingSize": "10L",
      "quantityLiters": 400.00,
      "piecesCount": 40,
      "boxesCount": 4,
      "piecesPerBox": 10
    }
  ]
}
```
