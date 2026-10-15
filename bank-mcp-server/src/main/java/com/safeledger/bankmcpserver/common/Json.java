package com.safeledger.bankmcpserver.common;

import tools.jackson.databind.json.JsonMapper;

/** One JSON mapper for tool results, stored arguments and audit rows. */
public final class Json {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private Json() {}

  public static String write(Object value) {
    return MAPPER.writeValueAsString(value);
  }

  public static <T> T read(String json, Class<T> type) {
    return MAPPER.readValue(json, type);
  }
}
