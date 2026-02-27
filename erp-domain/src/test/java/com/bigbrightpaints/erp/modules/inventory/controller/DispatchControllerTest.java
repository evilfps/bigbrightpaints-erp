package com.bigbrightpaints.erp.modules.inventory.controller;

import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationResponse;
import com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipDto;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DispatchControllerTest {

    @Mock
    private FinishedGoodsService finishedGoodsService;

    @Mock
    private SalesService salesService;

    @Test
    void confirmDispatch_callsSalesOnce_andDoesNotDoubleDispatchInventory() {
        DispatchController controller = new DispatchController(finishedGoodsService, salesService);
        Principal principal = () -> "factory.user";

        DispatchConfirmationRequest request = new DispatchConfirmationRequest(
                10L,
                List.of(new DispatchConfirmationRequest.LineConfirmation(
                        100L,
                        new BigDecimal("2.50"),
                        "Ship as-is"
                )),
                "Dispatch notes",
                null,
                999L
        );

        DispatchConfirmationResponse expected = new DispatchConfirmationResponse(
                10L,
                "PS-10",
                "DISPATCHED",
                Instant.now(),
                "factory.user",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                1L,
                2L,
                List.of(),
                null
        );
        when(finishedGoodsService.getDispatchConfirmation(10L)).thenReturn(expected);

        ResponseEntity<ApiResponse<DispatchConfirmationResponse>> response = controller.confirmDispatch(request, principal);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data()).isSameAs(expected);

        ArgumentCaptor<DispatchConfirmRequest> dispatchCaptor = ArgumentCaptor.forClass(DispatchConfirmRequest.class);
        verify(salesService).confirmDispatch(dispatchCaptor.capture());

        DispatchConfirmRequest dispatchRequest = dispatchCaptor.getValue();
        assertThat(dispatchRequest.packingSlipId()).isEqualTo(10L);
        assertThat(dispatchRequest.confirmedBy()).isEqualTo("factory.user");
        assertThat(dispatchRequest.overrideReason()).isNull();
        assertThat(dispatchRequest.overrideRequestId()).isEqualTo(999L);
        assertThat(dispatchRequest.lines()).hasSize(1);
        assertThat(dispatchRequest.lines().get(0).lineId()).isEqualTo(100L);
        assertThat(dispatchRequest.lines().get(0).shipQty()).isEqualByComparingTo("2.50");
        assertThat(dispatchRequest.lines().get(0).notes()).isEqualTo("Ship as-is");

        verify(finishedGoodsService).getDispatchConfirmation(10L);
        verifyNoMoreInteractions(salesService, finishedGoodsService);
    }

    @Test
    void getPendingSlips_filtersTerminalStatusesButKeepsBackorder() {
        DispatchController controller = new DispatchController(finishedGoodsService, salesService);
        when(finishedGoodsService.listPackagingSlips()).thenReturn(List.of(
                new PackagingSlipDto(1L, null, null, null, null, "PS-1", "PENDING", null, null, null, null, null, null, null, List.of()),
                new PackagingSlipDto(2L, null, null, null, null, "PS-2", "RESERVED", null, null, null, null, null, null, null, List.of()),
                new PackagingSlipDto(3L, null, null, null, null, "PS-3", "BACKORDER", null, null, null, null, null, null, null, List.of()),
                new PackagingSlipDto(4L, null, null, null, null, "PS-4", "CANCELLED", null, null, null, null, null, null, null, List.of()),
                new PackagingSlipDto(5L, null, null, null, null, "PS-5", "DISPATCHED", null, null, null, null, null, null, null, List.of())
        ));

        ResponseEntity<ApiResponse<List<PackagingSlipDto>>> response = controller.getPendingSlips();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data())
                .extracting(PackagingSlipDto::slipNumber)
                .containsExactly("PS-1", "PS-2", "PS-3");
        verify(finishedGoodsService).listPackagingSlips();
        verifyNoMoreInteractions(finishedGoodsService, salesService);
    }
}
