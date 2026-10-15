package com.safeledger.bankmcpserver.injection;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Lightweight prompt injection detector for free text that comes from the bank (merchant,
 * description, payee name, note, dispute reason). A match flags the field; the data is still
 * returned so the customer can see it. This is a tripwire, not the main defence: the main defence
 * is that the model cannot complete any write on its own.
 */
@Component
public class InjectionDetector {

  public record Rule(String name, Pattern pattern) {}

  private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

  private static final List<Rule> RULES =
      List.of(
          rule(
              "ignore_instructions",
              "\\b(ignore|disregard|forget|override)\\b.{0,40}\\b(instructions?|rules|guidelines|prompt|policy|policies)\\b"),
          rule(
              "fake_system_message",
              "\\b(system|developer)\\s*(message|prompt|note|instruction)s?\\s*[:\\-]"),
          rule(
              "addressed_to_assistant",
              "\\b(note\\s+to\\s+(the\\s+)?(assistant|ai|model|agent)|(assistant|ai|agent|model)\\s*[:(,])"),
          rule(
              "role_change",
              "\\byou\\s+are\\s+now\\b|\\b(admin|developer|god|debug)\\s+mode\\b|\\bact\\s+as\\b"),
          rule("new_instruction", "\\bnew\\s+(instruction|instructions|task|rules?)\\b"),
          rule("fake_markup", "</?\\s*[a-z_]*(data|system|instruction|tool|context)[a-z_]*\\s*>"),
          rule(
              "prompt_extraction",
              "\\b(reveal|print|show|repeat|output)\\b.{0,30}\\b(system\\s+prompt|instructions|tool\\s+list|hidden)\\b"),
          rule(
              "self_approval",
              "\\b(confirm|approve|execute)\\b.{0,30}\\b(yourself|on\\s+your\\s+own|without\\s+asking)\\b|\\bpre-?approved\\b|\\balready\\s+(agreed|approved|confirmed)\\b"),
          rule(
              "tool_name",
              "\\b(request_transfer|request_dispute|request_card_freeze|request_card_unfreeze|list_accounts|search_transactions)\\b"),
          rule("concealment", "\\bdo\\s+not\\s+(tell|mention|inform|show)\\b"),
          rule("other_customers", "\\b(every|all|other)\\s+(other\\s+)?customers?\\b"),
          rule(
              "move_funds",
              "\\b(transfer|send|move)\\b.{0,40}\\b(full|entire|all)\\s+(the\\s+)?(balance|funds|money)\\b"));

  public List<String> scan(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    List<String> matched = new ArrayList<>();
    for (Rule rule : RULES) {
      if (rule.pattern().matcher(text).find()) {
        matched.add(rule.name());
      }
    }
    return matched;
  }

  private static Rule rule(String name, String regex) {
    return new Rule(name, Pattern.compile(regex, FLAGS));
  }
}
