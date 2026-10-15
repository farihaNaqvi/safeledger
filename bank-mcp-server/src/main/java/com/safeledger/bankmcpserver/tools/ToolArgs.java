package com.safeledger.bankmcpserver.tools;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/** Argument parsing with errors the model can act on. Money is parsed from text, never double. */
final class ToolArgs {

  private ToolArgs() {}

  static UUID id(String name, String value) {
    if (value == null || value.isBlank()) {
      throw new ToolError("INVALID_ARGUMENT", name + " is required.");
    }
    try {
      return UUID.fromString(value.strip());
    } catch (IllegalArgumentException e) {
      throw new ToolError("INVALID_ARGUMENT", name + " must be an ID returned by another tool.");
    }
  }

  static BigDecimal amount(String name, String value, boolean required) {
    if (value == null || value.isBlank()) {
      if (required) {
        throw new ToolError("INVALID_ARGUMENT", name + " is required.");
      }
      return null;
    }
    try {
      BigDecimal amount = new BigDecimal(value.strip().replace(",", ""));
      if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
        throw new ToolError(
            "INVALID_ARGUMENT", name + " must be a positive amount with at most 2 decimals.");
      }
      return amount.setScale(2);
    } catch (NumberFormatException e) {
      throw new ToolError("INVALID_ARGUMENT", name + " must be a number such as 1500.00.");
    }
  }

  static String date(String name, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value.strip()).toString();
    } catch (DateTimeParseException e) {
      throw new ToolError("INVALID_ARGUMENT", name + " must be a date like 2026-10-15.");
    }
  }

  static String text(String name, String value, int maxLength, boolean required) {
    if (value == null || value.isBlank()) {
      if (required) {
        throw new ToolError("INVALID_ARGUMENT", name + " is required.");
      }
      return null;
    }
    String stripped = value.strip();
    if (stripped.length() > maxLength) {
      throw new ToolError(
          "INVALID_ARGUMENT", name + " must be at most " + maxLength + " characters.");
    }
    return stripped;
  }
}
