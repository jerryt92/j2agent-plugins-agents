package io.github.jerryt92.j2agent.agent.qa;

import io.github.jerryt92.j2agent.plugins.tool.KnowledgeRepoGrepTools;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.agent.inf.constant.AgentThinkingOverride;
import io.github.jerryt92.j2agent.service.llm.agent.inf.feature.ExternalSkills;
import io.github.jerryt92.j2agent.service.rag.inf.AbstractCollectionKbRetriever;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMetadataService;
import io.github.jerryt92.j2agent.tools.MathTool;
import io.github.jerryt92.j2agent.tools.WebTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 通用聊天助手Agent
 */
@Slf4j
@Component
public class AssistantReactAgent extends AiAgent implements ExternalSkills {
    private final MathTool mathTool;
    private final WebTool webTool;
    private final KnowledgeRepoGrepTools knowledgeRepoGrepTools;
    private final AbstractCollectionKbRetriever j2AgentDocsRetriever;

    @Override
    public String getAgentId() {
        return "j2agent-qa-assistant";
    }

    @Override
    public String getAgentName() {
        return "J2Agent 文档问答助手";
    }

    @Override
    public String getAgentDescription() {
        return "J2Agent 文档问答助手";
    }

    @Override
    public String loadSystemPrompt() {
        return "你是 J2Agent AI，是解答关于 J2Agent 平台知识的问答助手。你的所属组织是 J2Agent AI 平台";
    }

    @Override
    public int getSort() {
        return 2;
    }

    @Override
    public String getLogo() {
        return "💬";
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
            MathTool mathTool,
            @Qualifier("j2AgentDocsRetriever") AbstractCollectionKbRetriever j2AgentDocsRetriever, WebTool webTool, KnowledgeRepoMetadataService knowledgeRepoMetadataService) {
        this.mathTool = mathTool;
        this.j2AgentDocsRetriever = j2AgentDocsRetriever;
        this.webTool = webTool;
        /**
         * 相对 com.nms.ai.knowledge.repo.root-path 的 Wiki 子目录，与部署目录 wiki/info.json 一致。
         */
        this.knowledgeRepoGrepTools = new KnowledgeRepoGrepTools(knowledgeRepoMetadataService, "j2agent-docs");
    }

    @Override
    protected Object[] buildTools() {
        return new Object[]{mathTool, webTool, knowledgeRepoGrepTools};
    }

    @Override
    protected AbstractCollectionKbRetriever buildDocumentRetriever() {
        return j2AgentDocsRetriever;
    }

    @Override
    public boolean isRagSourceDisplayEnabled() {
        return true;
    }

    /**
     * 每次调用 grep_knowledge_repo 并阅读结果，grep 有相关内容则据此作答（不省略不总结不无脑照搬），无相关内容则用 RAG 上下文作答。
     */
    @Override
    protected QueryAugmenter buildQueryAugmenter() {
        PromptTemplate promptTemplate = new PromptTemplate("""
                以下为向量检索（RAG）提供的参考上下文。
                
                ---------------------
                {context}
                ---------------------
                
                规则：
                1. 必须先调用 grep_knowledge_repo 并认真阅读其返回结果。
                2. 阅读 grep 结果后判断是否包含与用户问题相关的内容：如果有相关内容，以 grep 结果为依据作答，禁止省略或总结原文内容，但也不要无脑照搬，需要围绕用户问题合理呈现；如果没有相关内容，则基于上述 RAG 上下文作答。
                3. 不得凭常识回答；grep 和 RAG 均无相关内容时说明无法回答，不要编造或猜测。
                4. 禁止提及答案来源于"grep""向量检索""RAG""知识库"等内部机制，避免"根据上下文""所提供的资料"等套话。
                
                用户问题：{query}
                
                回答：
                """);
        return ContextualQueryAugmenter.builder()
                .promptTemplate(promptTemplate)
                .allowEmptyContext(true)
                .build();
    }


}