package com.safeledger.agentservice;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * A chat model that plays a script instead of calling an LLM, and records every prompt it was
 * given. Each step sees the prompt, so it can react to tool results. When the script runs out it
 * keeps returning {@link #fallback}.
 */
public class ScriptedChatModel implements ChatModel {

  static final String MODEL = "scripted-model";

  final Deque<Function<Prompt, ChatResponse>> script = new ConcurrentLinkedDeque<>();
  final List<Prompt> prompts = new CopyOnWriteArrayList<>();
  volatile Function<Prompt, ChatResponse> fallback = p -> reply("(script finished)");

  void reset() {
    script.clear();
    prompts.clear();
    fallback = p -> reply("(script finished)");
  }

  ScriptedChatModel then(Function<Prompt, ChatResponse> step) {
    script.add(step);
    return this;
  }

  ScriptedChatModel thenReply(String text) {
    return then(p -> reply(text));
  }

  ScriptedChatModel thenCall(String tool, String argumentsJson) {
    return then(p -> toolCall(tool, argumentsJson));
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    prompts.add(prompt);
    Function<Prompt, ChatResponse> step = script.poll();
    return (step == null ? fallback : step).apply(prompt);
  }

  @Override
  public ChatOptions getOptions() {
    return ToolCallingChatOptions.builder().model(MODEL).temperature(0.2).build();
  }

  /** Every scripted response reports this many tokens. */
  static final int INPUT_TOKENS = 100;

  static final int OUTPUT_TOKENS = 10;

  static ChatResponse reply(String text) {
    return response(AssistantMessage.builder().content(text).build());
  }

  static ChatResponse toolCall(String tool, String argumentsJson) {
    AssistantMessage.ToolCall call =
        new AssistantMessage.ToolCall("call-" + System.nanoTime(), "function", tool, argumentsJson);
    return response(AssistantMessage.builder().content("").toolCalls(List.of(call)).build());
  }

  /** The same response preceded by a thinking generation, the way Anthropic models return it. */
  static ChatResponse afterThinking(ChatResponse response) {
    AssistantMessage thinking =
        AssistantMessage.builder()
            .content("The customer wants their balance.")
            .properties(Map.of("signature", "sig"))
            .build();
    List<Generation> generations = new ArrayList<>();
    generations.add(new Generation(thinking));
    generations.addAll(response.getResults());
    return new ChatResponse(generations, response.getMetadata());
  }

  private static ChatResponse response(AssistantMessage message) {
    return new ChatResponse(
        List.of(new Generation(message)),
        ChatResponseMetadata.builder()
            .usage(new DefaultUsage(INPUT_TOKENS, OUTPUT_TOKENS))
            .build());
  }

  /** The text of the most recent tool result in the prompt. */
  static String lastToolResult(Prompt prompt) {
    List<Message> messages = prompt.getInstructions();
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i) instanceof ToolResponseMessage tool) {
        return tool.getResponses().getLast().responseData();
      }
    }
    return null;
  }
}
