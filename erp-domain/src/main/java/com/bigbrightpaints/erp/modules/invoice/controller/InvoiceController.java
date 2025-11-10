package com.bigbrightpaints.erp.modules.invoice.controller;

import com.bigbrightpaints.erp.modules.invoice.dto.InvoiceDto;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<InvoiceDto>>> listInvoices() {
        return ResponseEntity.ok(ApiResponse.success(invoiceService.listInvoices()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<InvoiceDto>> getInvoice(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(invoiceService.getInvoice(id)));
    }

    @GetMapping("/dealers/{dealerId}")
    public ResponseEntity<ApiResponse<List<InvoiceDto>>> dealerInvoices(@PathVariable Long dealerId) {
        return ResponseEntity.ok(ApiResponse.success(invoiceService.listDealerInvoices(dealerId)));
    }
}
