package com.smartjobhunt.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuration for OpenAPI/Swagger documentation.
 * 
 * <p>Provides API metadata and customizes the Swagger UI.
 * Access the Swagger UI at: http://localhost:8080/swagger-ui.html
 * Access the OpenAPI spec at: http://localhost:8080/v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Value("${server.port:8080}")
    private String serverPort;

    @Bean
    public OpenAPI smartJobHuntOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Smart Job Hunt API")
                        .description("REST API for intelligent job matching using GCP Vertex AI Search and Gemini. "
                                + "Upload job descriptions, search through indexed jobs, and match resumes "
                                + "against job profiles with explainable AI scoring.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Smart Job Hunt Team")
                                .email("support@smartjobhunt.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0.html")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:" + serverPort)
                                .description("Local Development Server"),
                        new Server()
                                .url("https://api.smartjobhunt.com")
                                .description("Production Server")));
    }
}
