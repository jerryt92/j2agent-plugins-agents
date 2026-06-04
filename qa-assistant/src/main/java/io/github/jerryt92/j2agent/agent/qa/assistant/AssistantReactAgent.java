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

    /**
     * QA 助手采用严格 RAG：无命中时不保留原问题而提示超出知识库；有命中时仅允许依据检索上下文作答。
     */
    @Override
    protected QueryAugmenter buildQueryAugmenter() {
        PromptTemplate promptTemplate = new PromptTemplate("""
                以下为知识库检索上下文。请仅依据上下文回答，不得引入上下文以外的产品事实。
                
                ---------------------
                {context}
                ---------------------
                
                规则：
                1. 答案须能在上下文中找到明确依据；不足以回答时，请说明无法根据知识库作答，不要编造或猜测。
                2. 避免“根据上下文”“所提供的资料”等套话。
                
                用户问题：{query}
                
                回答：
                """);
        PromptTemplate emptyContextPromptTemplate = new PromptTemplate("""
                当前问题在知识库中未检索到相关内容。
                请礼貌告知用户：该问题超出当前知识库范围，无法作答；不要编造或凭常识补充产品信息。
                """);
        return ContextualQueryAugmenter.builder()
                .promptTemplate(promptTemplate)
                .emptyContextPromptTemplate(emptyContextPromptTemplate)
                .allowEmptyContext(false)
                .build();
    }
}
