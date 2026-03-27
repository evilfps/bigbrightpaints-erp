package com.bigbrightpaints.erp.core.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;

@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI erpOpenApi() {
    return new OpenAPI()
        .servers(List.of(new Server().url("/").description("Default")))
        .info(
            new Info()
                .title("BigBright ERP Domain API")
                .description("Core APIs for the scoped-account multi-tenant ERP platform")
                .version("v1")
                .license(new License().name("Proprietary")))
        .externalDocs(
            new ExternalDocumentation()
                .description("ERP Documentation")
                .url("https://docs.bigbrightpaints.com"));
  }
}
