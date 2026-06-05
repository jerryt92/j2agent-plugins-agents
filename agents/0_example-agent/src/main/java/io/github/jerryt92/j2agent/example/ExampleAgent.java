package io.github.jerryt92.j2agent.example;

import io.github.jerryt92.j2agent.service.llm.agent.AiAgent;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 最小 Agent 示例：仅实现 {@link AiAgent} 四个必填方法，无工具 / Skill / RAG。
 * 复制本工程后请修改 agentId、名称与 system-prompt.md。
 */
@Component
public class ExampleAgent extends AiAgent {

    @Override
    public String getAgentId() {
        return "example_agent";
    }

    @Override
    public String getAgentName() {
        return "示例 Agent";
    }

    @Override
    public String getAgentDescription() {
        return "最小接入示例，用于验证插件加载与对话链路。";
    }

    @Override
    public boolean isQaTemplateEnabled() {
        return true;
    }

    @Override
    public String loadSystemPrompt() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("system-prompt.md")) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // fallback
        }
        return "你是示例助手，回答应简洁准确。";
    }
}
