package com.bigbrightpaints.erp.modules.factory.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.modules.factory.dto.PackagingSizeMappingDto;
import com.bigbrightpaints.erp.modules.factory.dto.PackagingSizeMappingRequest;
import com.bigbrightpaints.erp.modules.factory.service.PackagingMaterialService;

@Tag("critical")
@ExtendWith(MockitoExtension.class)
class PackagingMappingControllerTest {

  @Mock private PackagingMaterialService packagingMaterialService;

  @Test
  void listMappings_returnsPackagingSetupRules() {
    PackagingMappingController controller =
        new PackagingMappingController(packagingMaterialService);
    List<PackagingSizeMappingDto> mappings = List.of(mappingDto(11L, "1L", true));
    when(packagingMaterialService.listMappings()).thenReturn(mappings);

    var response = controller.listMappings();

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message()).isEqualTo("Packaging setup rules");
    assertThat(response.getBody().data()).isEqualTo(mappings);
    verify(packagingMaterialService).listMappings();
  }

  @Test
  void listActiveMappings_returnsActivePackagingSetupRules() {
    PackagingMappingController controller =
        new PackagingMappingController(packagingMaterialService);
    List<PackagingSizeMappingDto> mappings = List.of(mappingDto(12L, "5L", true));
    when(packagingMaterialService.listActiveMappings()).thenReturn(mappings);

    var response = controller.listActiveMappings();

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message()).isEqualTo("Active packaging setup rules");
    assertThat(response.getBody().data()).isEqualTo(mappings);
    verify(packagingMaterialService).listActiveMappings();
  }

  @Test
  void createMapping_delegatesToPackagingMaterialService() {
    PackagingMappingController controller =
        new PackagingMappingController(packagingMaterialService);
    PackagingSizeMappingRequest request =
        new PackagingSizeMappingRequest("1L", 21L, 2, 12, BigDecimal.ONE);
    PackagingSizeMappingDto created = mappingDto(13L, "1L", true);
    when(packagingMaterialService.createMapping(request)).thenReturn(created);

    var response = controller.createMapping(request);

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message()).isEqualTo("Packaging setup rule created");
    assertThat(response.getBody().data()).isEqualTo(created);
    verify(packagingMaterialService).createMapping(request);
  }

  @Test
  void updateMapping_delegatesToPackagingMaterialService() {
    PackagingMappingController controller =
        new PackagingMappingController(packagingMaterialService);
    PackagingSizeMappingRequest request =
        new PackagingSizeMappingRequest("500ML", 22L, 3, 24, new BigDecimal("0.5"));
    PackagingSizeMappingDto updated = mappingDto(14L, "500ML", true);
    when(packagingMaterialService.updateMapping(14L, request)).thenReturn(updated);

    var response = controller.updateMapping(14L, request);

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message()).isEqualTo("Packaging setup rule updated");
    assertThat(response.getBody().data()).isEqualTo(updated);
    verify(packagingMaterialService).updateMapping(14L, request);
  }

  @Test
  void deactivateMapping_delegatesToPackagingMaterialService() {
    PackagingMappingController controller =
        new PackagingMappingController(packagingMaterialService);

    var response = controller.deactivateMapping(15L);

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message()).isEqualTo("Packaging setup rule deactivated");
    assertThat(response.getBody().data()).isNull();
    verify(packagingMaterialService).deactivateMapping(15L);
  }

  private PackagingSizeMappingDto mappingDto(Long id, String packagingSize, boolean active) {
    return new PackagingSizeMappingDto(
        id,
        UUID.randomUUID(),
        packagingSize,
        99L,
        "PACK-99",
        "Bucket",
        1,
        12,
        BigDecimal.ONE,
        active);
  }
}
