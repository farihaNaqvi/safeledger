package com.safeledger.bankmcpserver.identity;

import com.safeledger.bankmcpserver.common.CorrelationIdFilter;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import java.util.HashMap;
import java.util.Map;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replaces the auto-configured Streamable HTTP transport with one that copies the verified caller
 * identity (set by {@link IdentityFilter}) and the correlation ID into the MCP transport context of
 * every request. Tools read the identity from there and nowhere else.
 */
@Configuration(proxyBeanMethods = false)
public class McpTransportConfig {

  public static final String IDENTITY_KEY = "safeledger.identity";
  public static final String CORRELATION_KEY = "safeledger.correlationId";
  public static final String OBSERVATION_KEY = "safeledger.observation";

  @Bean
  WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
      @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
      McpServerStreamableHttpProperties serverProperties,
      ObservationRegistry observations) {
    return WebMvcStreamableServerTransportProvider.builder()
        .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
        .mcpEndpoint(serverProperties.getMcpEndpoint())
        .keepAliveInterval(serverProperties.getKeepAliveInterval())
        .disallowDelete(serverProperties.isDisallowDelete())
        .contextExtractor(
            request -> {
              Map<String, Object> context = new HashMap<>();
              request
                  .attribute(IdentityFilter.ATTRIBUTE)
                  .ifPresent(identity -> context.put(IDENTITY_KEY, identity));
              request
                  .attribute(CorrelationIdFilter.ATTRIBUTE)
                  .ifPresent(id -> context.put(CORRELATION_KEY, id));
              // The incoming HTTP request's span, so tool spans join the caller's trace even if
              // the tool runs on another thread.
              Observation current = observations.getCurrentObservation();
              if (current != null) {
                context.put(OBSERVATION_KEY, current);
              }
              return McpTransportContext.create(context);
            })
        .build();
  }
}
