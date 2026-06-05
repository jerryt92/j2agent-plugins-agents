package io.github.jerryt92.j2agent.agent.qa.assistant;

import io.github.jerryt92.j2agent.rag.AbstractCollectionKbRetriever;
import io.github.jerryt92.j2agent.service.llm.agent.AgentThinkingOverride;
import io.github.jerryt92.j2agent.service.llm.agent.AiAgent;
import io.github.jerryt92.j2agent.service.llm.mcp.McpService;
import io.github.jerryt92.j2agent.tools.MathTool;
import io.github.jerryt92.j2agent.agent.qa.prompts.SystemPrompts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 通用聊天助手Agent
 */
@Slf4j
@Component
public class AssistantReactAgent extends AiAgent {
    private final MathTool mathTool;
    private final McpService mcpService;
    private final AbstractCollectionKbRetriever documentRetriever;

    @Override
    public String getAgentId() {
        return "chat_assistant";
    }

    @Override
    public String getAgentName() {
        return "聊天助手";
    }

    @Override
    public String getAgentDescription() {
        return "通用聊天助手";
    }

    @Override
    public String loadSystemPrompt() {
        return SystemPrompts.GENERAL_ASSISTANT;
    }

    @Override
    public int getSort() {
        return 1;
    }

    @Override
    public boolean isQaTemplateEnabled() {
        return true;
    }

    @Override
    public AgentThinkingOverride getThinkingOverride() {
        return AgentThinkingOverride.OFF;
    }

    public AssistantReactAgent(
            MathTool mathTool,
            McpService mcpService,
            @Qualifier("QaAssistantKbRetriever") AbstractCollectionKbRetriever documentRetriever) {
        this.mathTool = mathTool;
        this.mcpService = mcpService;
        this.documentRetriever = documentRetriever;
    }

    /**
     * 合并本地 {@link MathTool} 与 MCP 工具；{@code Arrays.copyOf} 只会拉长数组并用 null 填充，不能拼接两段回调。
     */
    @Override
    protected ToolCallback[] buildToolCallbacks() {
        List<ToolCallback> list = new ArrayList<>(Arrays.asList(ToolCallbacks.from(mathTool)));
        ToolCallback[] mcpCallbacks = mcpService.getToolCallbackProvider().getToolCallbacks();
        if (mcpCallbacks != null) {
            for (ToolCallback cb : mcpCallbacks) {
                if (cb != null) {
                    list.add(cb);
                }
            }
        }
        log.info("MCP tool callbacks merged for chat assistant.");
        return list.toArray(ToolCallback[]::new);
    }

    @Override
    protected AbstractCollectionKbRetriever buildDocumentRetriever() {
        return documentRetriever;
    }
}
