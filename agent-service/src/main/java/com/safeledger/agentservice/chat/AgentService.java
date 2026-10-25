package com.safeledger.agentservice.chat;

import com.safeledger.agentservice.common.ApiException;
import com.safeledger.agentservice.common.ApiExceptionHandler;
import com.safeledger.agentservice.common.BrowserRequestFilter;
import com.safeledger.agentservice.config.AgentProperties;
import com.safeledger.agentservice.guardrail.Guardrail;
import com.safeledger.agentservice.mcp.ActionsClient;
import com.safeledger.agentservice.mcp.SessionMcpClients;
import com.safeledger.agentservice.session.CustomerSession;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * One chat turn: guardrail on the message, the model with the session's MCP tools in a loop that
 * this class runs itself (so the tool-call cap, round cap and timeout hold for every model
 * provider), guardrail on the reply, then report any pending actions the turn created so the UI can
 * show Confirm buttons. The model is never given a way to confirm them.
 */
@Service
public class AgentService {

  private static final Logger log = LoggerFactory.getLogger(AgentService.class);

  static final String TIMEOUT_REPLY =
      "Sorry, that took too long. Please try again, or ask in a simpler way.";
  static final String UNFINISHED_REPLY =
      "Sorry, I couldn't finish that request. Please try again with more detail.";

  public record PendingView(UUID id, String summary, Instant expiresAt) {}

  /** Model tokens for the whole turn, summed over every model round. */
  public record TokenUsage(long inputTokens, long outputTokens) {
    static final TokenUsage NONE = new TokenUsage(0, 0);

    TokenUsage plus(ChatResponse response) {
      Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
      if (usage == null) {
        return this;
      }
      return new TokenUsage(
          inputTokens + orZero(usage.getPromptTokens()),
          outputTokens + orZero(usage.getCompletionTokens()));
    }

    private static long orZero(Integer value) {
      return value == null ? 0 : value;
    }
  }

  public record TurnResult(
      String reply,
      List<PendingView> pendingActions,
      int toolCalls,
      boolean guardrailIntervened,
      String guardrail,
      String promptVersion,
      TokenUsage usage) {}

  private final ChatModel chatModel;
  private final SessionMcpClients mcpClients;
  private final ActionsClient actions;
  private final Guardrail guardrail;
  private final SystemPrompt systemPrompt;
  private final AgentProperties.Agent config;
  private final ChatMemory memory;
  private final ToolCallingManager toolCallingManager;
  private final ChatMetrics metrics;
  private final ObservationRegistry observations;
  private final AgentProperties.Cost cost;
  private final ExecutorService turns = Executors.newVirtualThreadPerTaskExecutor();

  public AgentService(
      ChatModel chatModel,
      SessionMcpClients mcpClients,
      ActionsClient actions,
      Guardrail guardrail,
      SystemPrompt systemPrompt,
      AgentProperties properties,
      ChatMetrics metrics,
      ObservationRegistry observations) {
    this.chatModel = chatModel;
    this.metrics = metrics;
    this.observations = observations;
    this.cost = properties.cost();
    this.mcpClients = mcpClients;
    this.actions = actions;
    this.guardrail = guardrail;
    this.systemPrompt = systemPrompt;
    this.config = properties.agent();
    this.memory =
        MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository())
            .maxMessages(config.memoryMessages())
            .build();
    this.toolCallingManager =
        DefaultToolCallingManager.builder()
            .maxTotalToolCalls(config.maxToolCallsPerTurn())
            .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
            .toolCallbackResolver(UnknownTool::new)
            .resolutionFallbackEnabled(true)
            .observationRegistry(observations)
            .build();
  }

  public TurnResult turn(CustomerSession session, String message) {
    Set<UUID> before = actionIds(session);
    String correlationId = MDC.get(BrowserRequestFilter.MDC_KEY);
    // The turn runs on its own thread, so the request's span is passed on explicitly; model calls,
    // tool calls and MCP requests become its children.
    Observation parent = observations.getCurrentObservation();
    Future<TurnResult> future =
        turns.submit(
            () -> {
              if (correlationId != null) {
                MDC.put(BrowserRequestFilter.MDC_KEY, correlationId);
              }
              Observation observation =
                  Observation.createNotStarted("safeledger.chat.turn", observations)
                      .contextualName("chat turn")
                      .parentObservation(parent)
                      .lowCardinalityKeyValue("prompt", systemPrompt.version())
                      .start();
              try (Observation.Scope scope = observation.openScope()) {
                TurnResult result = run(session, message, before);
                observation
                    .highCardinalityKeyValue(
                        "gen_ai.usage.input_tokens", String.valueOf(result.usage().inputTokens()))
                    .highCardinalityKeyValue(
                        "gen_ai.usage.output_tokens", String.valueOf(result.usage().outputTokens()))
                    .highCardinalityKeyValue(
                        "safeledger.cost_usd",
                        cost.usd(result.usage().inputTokens(), result.usage().outputTokens())
                            .toPlainString());
                return result;
              } catch (RuntimeException e) {
                observation.error(e);
                throw e;
              } finally {
                observation.stop();
                MDC.remove(BrowserRequestFilter.MDC_KEY);
              }
            });
    try {
      return future.get(config.turnTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      log.warn("Turn timed out after {}", config.turnTimeout());
      return result(TIMEOUT_REPLY, newPending(session, before), 0, false, TokenUsage.NONE);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException cause) {
        throw cause;
      }
      throw new IllegalStateException("Turn failed", e.getCause());
    }
  }

  /** Records what the app did, so later turns know the outcome without the model claiming it. */
  public void recordAppEvent(CustomerSession session, String event) {
    memory.add(session.sessionId(), List.of(new AssistantMessage("[App] " + event)));
  }

  private TurnResult run(CustomerSession session, String message, Set<UUID> before) {
    Guardrail.Result input = guardrail.check(Guardrail.Source.INPUT, message);
    metrics.guardrail(Guardrail.Source.INPUT, input);
    if (input.blocked()) {
      return result(input.text(), List.of(), 0, true, TokenUsage.NONE);
    }
    // Masked when the customer typed a card or account number: the model only sees the mask.
    String customerText = input.text();
    // Out of band from the model: the MCP server grounds write requests in these words.
    actions.recordUtterance(session, customerText);

    McpSyncClient client;
    try {
      client = mcpClients.forSession(session);
    } catch (RuntimeException e) {
      log.warn("MCP server unreachable: {}", e.getMessage());
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          ApiExceptionHandler.BANK_UNAVAILABLE,
          ApiExceptionHandler.BANK_UNAVAILABLE_DETAIL);
    }
    List<ToolCallback> tools =
        List.of(
            SyncMcpToolCallbackProvider.builder()
                .mcpClients(client)
                .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                .build()
                .getToolCallbacks());
    // Start from the model's configured options (model name, temperature) and add the tools, the
    // same way ChatClient does. In Spring AI 2.0 a ChatModel only advertises tools and returns the
    // calls; executing them is left to the caller, which is exactly what this loop needs.
    ChatOptions.Builder<?> builder = chatModel.getOptions().mutate();
    if (!(builder instanceof ToolCallingChatOptions.Builder<?> toolBuilder)) {
      throw new IllegalStateException("Chat model does not support tool calling");
    }
    toolBuilder.toolCallbacks(tools);
    ChatOptions options = builder.build();

    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(systemPrompt.render()));
    messages.addAll(memory.get(session.sessionId()));
    messages.add(new UserMessage(customerText));
    Prompt prompt = new Prompt(messages, options);

    ChatResponse response = chatModel.call(prompt);
    TokenUsage usage = TokenUsage.NONE.plus(response);
    int rounds = 1;
    int toolCalls = 0;
    List<String> toolNames = new ArrayList<>();
    while (response.hasToolCalls() && rounds < config.maxModelRoundsPerTurn()) {
      answer(response).getToolCalls().forEach(call -> toolNames.add(call.name()));
      toolCalls += answer(response).getToolCalls().size();
      ToolExecutionResult executed = toolCallingManager.executeToolCalls(prompt, response);
      prompt = new Prompt(withoutNarration(executed.conversationHistory()), options);
      response = chatModel.call(prompt);
      usage = usage.plus(response);
      rounds++;
    }

    String reply =
        response.hasToolCalls() || answer(response) == null
            ? UNFINISHED_REPLY
            : answer(response).getText();
    if (reply == null || reply.isBlank()) {
      reply = UNFINISHED_REPLY;
    }
    if (cutOff(response)) {
      log.warn("Reply cut off at the model's max-tokens limit");
    }
    Guardrail.Result output = guardrail.check(Guardrail.Source.OUTPUT, reply);
    metrics.guardrail(Guardrail.Source.OUTPUT, output);
    boolean firstTurn = memory.get(session.sessionId()).isEmpty();
    List<PendingView> pending = newPending(session, before);
    List<Message> remembered = new ArrayList<>();
    remembered.add(new UserMessage(customerText));
    if (!toolNames.isEmpty()) {
      remembered.add(new AssistantMessage("[App] " + toolNote(toolNames, pending)));
    }
    remembered.add(new AssistantMessage(output.text()));
    memory.add(session.sessionId(), remembered);
    metrics.turn(chatModel.getOptions().getModel(), usage, firstTurn);
    // Fields for CloudWatch metric filters in AWS (cost per session without a metrics agent).
    log.atInfo()
        .addKeyValue("costUsd", cost.usd(usage.inputTokens(), usage.outputTokens()).doubleValue())
        .addKeyValue("firstTurn", firstTurn)
        .log(
            "Turn finished: toolCalls={} rounds={} inputTokens={} outputTokens={} prompt={}",
            toolCalls,
            rounds,
            usage.inputTokens(),
            usage.outputTokens(),
            systemPrompt.version());
    return result(
        output.text(), pending, toolCalls, input.intervened() || output.intervened(), usage);
  }

  /**
   * What the tools did this turn, kept in memory so later turns do not have to trust (or doubt) the
   * model's own earlier wording.
   */
  static String toolNote(List<String> toolNames, List<PendingView> pending) {
    StringBuilder note =
        new StringBuilder("Tools used this turn: ")
            .append(String.join(", ", toolNames.stream().distinct().toList()))
            .append('.');
    if (pending.isEmpty()) {
      note.append(" No new request was created.");
    }
    pending.forEach(
        p ->
            note.append(" Request created, waiting for the customer to press Confirm: ")
                .append(p.summary())
                .append(" Expires ")
                .append(p.expiresAt())
                .append(" (UTC)."));
    return note.toString();
  }

  /**
   * Drops the text the model wrote next to its tool calls ("Let me check your cards") before the
   * history goes back to the model. Spring AI's Anthropic converter puts that text in front of the
   * thinking blocks when it rebuilds the message, while Claude wrote it after them; Claude's signed
   * thinking blocks are bound to the content before them, so the next round was rejected (HTTP 500
   * in the live eval). The tool calls and thinking blocks, which the next round needs, are kept.
   */
  static List<Message> withoutNarration(List<Message> history) {
    return history.stream()
        .map(
            m ->
                m instanceof AssistantMessage a
                        && a.hasToolCalls()
                        && a.getText() != null
                        && !a.getText().isEmpty()
                    ? (Message) a.mutate().content("").build()
                    : m)
        .toList();
  }

  /** Anthropic and Bedrock report "max_tokens", Ollama "length". */
  static boolean cutOff(ChatResponse response) {
    List<Generation> results = response.getResults();
    if (results.isEmpty() || results.getLast().getMetadata() == null) {
      return false;
    }
    String reason = results.getLast().getMetadata().getFinishReason();
    return reason != null
        && (reason.equalsIgnoreCase("max_tokens") || reason.equalsIgnoreCase("length"));
  }

  /**
   * The generation holding the reply text and tool calls. Anthropic puts each thinking block in its
   * own generation ahead of it; the other providers return just one.
   */
  private static AssistantMessage answer(ChatResponse response) {
    List<Generation> results = response.getResults();
    return results.isEmpty() ? null : results.getLast().getOutput();
  }

  private TurnResult result(
      String reply,
      List<PendingView> pending,
      int toolCalls,
      boolean intervened,
      TokenUsage usage) {
    return new TurnResult(
        reply, pending, toolCalls, intervened, guardrail.name(), systemPrompt.version(), usage);
  }

  private Set<UUID> actionIds(CustomerSession session) {
    return actions.list(session).stream().map(ActionsClient.Action::id).collect(Collectors.toSet());
  }

  private List<PendingView> newPending(CustomerSession session, Set<UUID> before) {
    return actions.list(session).stream()
        .filter(a -> "PENDING".equals(a.status()) && !before.contains(a.id()))
        .map(a -> new PendingView(a.id(), a.summary(), a.expiresAt()))
        .toList();
  }

  @PreDestroy
  void shutdown() {
    turns.shutdownNow();
  }

  /**
   * Answers any tool name the model invents (for example a "confirm" tool) with an error instead of
   * failing the turn. There is no such tool, and the model is told so.
   */
  record UnknownTool(String name) implements ToolCallback {
    @Override
    public ToolDefinition getToolDefinition() {
      return ToolDefinition.builder()
          .name(name)
          .description("Not a SafeLedger tool")
          .inputSchema("{\"type\":\"object\",\"properties\":{}}")
          .build();
    }

    @Override
    public String call(String toolInput) {
      log.warn("Model called a tool that does not exist: {}", name);
      return "{\"error\":\"UNKNOWN_TOOL\",\"message\":\"There is no tool called "
          + name.replaceAll("[^A-Za-z0-9_.-]", "")
          + ". You cannot confirm or execute requests; the customer does that in the app.\"}";
    }
  }
}
