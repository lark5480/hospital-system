package com.hospital.core.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI hospitalOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("医院信息系统 API")
                        .description("模块化单体医院信息系统 RESTful API 文档")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Hospital System")
                                .email("hospital@example.com")))
                .addSecurityItem(new SecurityRequirement().addList("Bearer Auth"))
                .components(new io.swagger.v3.oas.models.Components()
                        .addSecuritySchemes("Bearer Auth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("JWT 认证令牌")));
    }
}
