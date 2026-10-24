package com.safeledger.agentservice.chat;

import com.safeledger.agentservice.config.AgentProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/** The versioned system prompt (resources/prompts/system-vN.md) with today's date filled in. */
@Component
public class SystemPrompt {

  private static final DateTimeFormatter NOW = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final String template;
  private final String version;
  private final ZoneId zone;

  public SystemPrompt(AgentProperties properties, ResourceLoader resourceLoader) {
    Resource resource = resourceLoader.getResource(properties.agent().systemPrompt());
    try {
      this.template = resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read system prompt " + resource, e);
    }
    String file = resource.getFilename() == null ? "unknown" : resource.getFilename();
    this.version = file.replaceFirst("\\.md$", "");
    this.zone = properties.agent().zone();
  }

  public String render() {
    ZonedDateTime now = ZonedDateTime.now(zone);
    return template
        .replace("{{today}}", now.toLocalDate().toString())
        .replace("{{now}}", now.format(NOW) + " (" + zone.getId() + ")")
        .replace("{{now_utc}}", now.withZoneSameInstant(ZoneOffset.UTC).format(NOW) + " UTC");
  }

  public String version() {
    return version;
  }
}
