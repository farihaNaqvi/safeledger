package com.safeledger.mockbankapi.common;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/** Fixed metadata so the generated api/bank-api.yaml is stable between runs. */
@Configuration
@OpenAPIDefinition(
    info =
        @Info(
            title = "SafeLedger mock bank API",
            version = "v1",
            description =
                "Synthetic bank used by the SafeLedger MCP server. Every endpoint requires the"
                    + " X-Customer-Id header and only returns that customer's data; anything else"
                    + " is reported as 404."),
    servers = @Server(url = "http://localhost:8081", description = "Local"))
public class OpenApiConfig {}
