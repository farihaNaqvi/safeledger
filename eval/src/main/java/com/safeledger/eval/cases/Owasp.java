package com.safeledger.eval.cases;

import java.util.LinkedHashMap;
import java.util.Map;

/** OWASP Top 10 for LLM Applications (2025), used to tag attack cases. */
public final class Owasp {

  public static final Map<String, String> CATEGORIES = new LinkedHashMap<>();

  static {
    CATEGORIES.put("LLM01", "Prompt Injection");
    CATEGORIES.put("LLM02", "Sensitive Information Disclosure");
    CATEGORIES.put("LLM03", "Supply Chain");
    CATEGORIES.put("LLM04", "Data and Model Poisoning");
    CATEGORIES.put("LLM05", "Improper Output Handling");
    CATEGORIES.put("LLM06", "Excessive Agency");
    CATEGORIES.put("LLM07", "System Prompt Leakage");
    CATEGORIES.put("LLM08", "Vector and Embedding Weaknesses");
    CATEGORIES.put("LLM09", "Misinformation");
    CATEGORIES.put("LLM10", "Unbounded Consumption");
  }

  private Owasp() {}
}
