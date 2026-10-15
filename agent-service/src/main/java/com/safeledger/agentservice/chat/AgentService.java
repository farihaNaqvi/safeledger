package com.safeledger.agentservice.chat;

import com.safeledger.agentservice.common.BrowserRequestFilter;
import com.safeledger.agentservice.config.AgentProperties;
import com.safeledger.agentservice.guardrail.Guardrail;
import com.safeledger.agentservice.mcp.ActionsClient;
import com.safeledger.agentservice.mcp.SessionMcpClients;
import com.safeledger.agentservice.session.CustomerSession;
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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
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

  public record TurnResult(
      String reply,
      List<PendingView> pendingActions,
      int toolCalls,
      boolean guardrailIntervened,
      String guardrail,
      String promptVersion) {}

  private final ChatModel chatModel;
  private final SessionMcpClients mcpClients;
  private final ActionsClient actions;
  private final Guardrail guardrail;
  private final SystemPrompt systemPrompt;
  private final AgentProperties.Agent config;
  private final ChatMemory memory;
  private final ToolCallingManager toolCallingManager;
  private final ExecutorService turns = Executors.newVirtualThreadPerTaskExecutor();

  public AgentService(
      ChatModel chatModel,
      SessionMcpClients mcpClients,
      ActionsClient actions,
      Guardrail guardrail,
      SystemPrompt systemPrompt,
      AgentProperties properties) {
    this.chatModel = chatModel;
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
            .build();
  }

  public TurnResult turn(CustomerSession session, String message) {
    Set<UUID> before = actionIds(session);
    String correlationId = MDC.get(BrowserRequestFilter.MDC_KEY);
    Future<TurnResult> future =
        turns.submit(
            () -> {
              if (correlationId != null) {
                MDC.put(BrowserRequestFilter.MDC_KEY, correlationId);
              }
              try {
                return run(session, message, before);
              } finally {
                MDC.remove(BrowserRequestFilter.MDC_KEY);
              }
            });
    try {
      return future.get(config.turnTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      log.warn("Turn timed out after {}", config.turnTimeout());
      return result(TIMEOUT_REPLY, newPending(session, before), 0, false);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Turn failed", e.getCause());
    }
  }

  /** Records what the app did, so later turns know the outcome without the model claiming it. */
  public void recordAppEvent(CustomerSession session, String event) {
    memory.add(session.sessionId(), List.of(new AssistantMessage("[App] " + event)));
  }

  private TurnResult run(CustomerSession session, String message, Set<UUID> before) {
    Guardrail.Result input = guardrail.check(Guardrail.Source.INPUT, message);
    if (input.intervened()) {
      return result(input.text(), List.of(), 0, true);
    }

    List<ToolCallback> tools =
        List.of(
            SyncMcpToolCallbackProvider.builder()
                .mcpClients(mcpClients.forSession(session))
                .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                .build()
                .getToolCallbacks());
    // In Spring AI 2.0 a ChatModel only advertises tools and returns the calls; executing them is
    // left to the caller, which is exactly what this loop needs.
    ToolCallingChatOptions options = ToolCallingChatOptions.builder().toolCallbacks(tools).build();

    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(systemPrompt.render()));
    messages.addAll(memory.get(session.sessionId()));
    messages.add(new UserMessage(message));
    Prompt prompt = new Prompt(messages, options);

    ChatResponse response = chatModel.call(prompt);
    int rounds = 1;
    int toolCalls = 0;
    while (response.hasToolCalls() && rounds < config.maxModelRoundsPerTurn()) {
      toolCalls += response.getResult().getOutput().getToolCalls().size();
      ToolExecutionResult executed = toolCallingManager.executeToolCalls(prompt, response);
      prompt = new Prompt(executed.conversationHistory(), options);
      response = chatModel.call(prompt);
      rounds++;
    }

    String reply =
        response.hasToolCalls() || response.getResult() == null
            ? UNFINISHED_REPLY
            : response.getResult().getOutput().getText();
    if (reply == null || reply.isBlank()) {
      reply = UNFINISHED_REPLY;
    }
    Guardrail.Result output = guardrail.check(Guardrail.Source.OUTPUT, reply);
    memory.add(
        session.sessionId(),
        List.of(new UserMessage(message), new AssistantMessage(output.text())));
    log.info(
        "Turn finished: toolCalls={} rounds={} prompt={}",
        toolCalls,
        rounds,
        systemPrompt.version());
    return result(output.text(), newPending(session, before), toolCalls, output.intervened());
  }

  private TurnResult result(
      String reply, List<PendingView> pending, int toolCalls, boolean intervened) {
    return new TurnResult(
        reply, pending, toolCalls, intervened, guardrail.name(), systemPrompt.version());
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
