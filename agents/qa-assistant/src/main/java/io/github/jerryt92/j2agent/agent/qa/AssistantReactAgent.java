package io.github.jerryt92.j2agent.agent.qa;

import io.github.jerryt92.j2agent.agent.qa.prompts.SystemPrompts;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.agent.inf.constant.AgentThinkingOverride;
import io.github.jerryt92.j2agent.service.llm.agent.inf.feature.ExternalSkills;
import io.github.jerryt92.j2agent.service.llm.agent.inf.feature.McpFeature;
import io.github.jerryt92.j2agent.tools.MathTool;
import io.github.jerryt92.j2agent.tools.WebTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 通用聊天助手Agent
 */
@Slf4j
@Component
public class AssistantReactAgent extends AiAgent implements ExternalSkills, McpFeature {
    private final MathTool mathTool;
    private final WebTool webTool;

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
        return AgentThinkingOverride.PROVIDER_DEFAULT;
    }

    public AssistantReactAgent(
            MathTool mathTool, WebTool webTool) {
        this.mathTool = mathTool;
        this.webTool = webTool;
    }

    @Override
    protected Object[] buildTools() {
        return new Object[]{mathTool, webTool};
    }
}