package io.github.jerryt92.j2agent.agent.qa;

import io.github.jerryt92.j2agent.model.I18nString;
import io.github.jerryt92.j2agent.plugins.tool.KnowledgeRepoGrepTools;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.agent.inf.constant.AgentThinkingOverride;
import io.github.jerryt92.j2agent.service.llm.agent.inf.feature.ExternalSkills;
import io.github.jerryt92.j2agent.service.rag.inf.AbstractCollectionKbRetriever;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeMarkdownImageRewriter;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMetadataService;
import io.github.jerryt92.j2agent.service.security.ResourceAccessService;
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

    /** 返回 Agent 多语言显示名称 */
    @Override
    public I18nString getAgentName() {
        return new I18nString()
                .zhCN("J2Agent 文档问答助手")
                .enUS("J2Agent Docs QA Assistant");
    }

    /** 返回 Agent 多语言描述 */
    @Override
    public I18nString getAgentDescription() {
        return new I18nString()
                .zhCN("J2Agent 文档问答助手")
                .enUS("J2Agent Docs QA Assistant");
    }

    @Override
    public String getOrchestrationPrompt() {
        return """
                J2Agent 平台文档 Wiki（j2agent-docs 知识库）；grep + 向量 RAG 融合检索。
                典型问法：平台功能操作步骤、Agent 开发指引、RAG/插件配置说明、故障排查；
                亦可能覆盖 J2Agent 产品与开发文档类问题。""";
    }

    @Override
    public String loadSystemPrompt() {
        return """
                你是 J2Agent AI，是解答关于 J2Agent 平台知识的问答助手。你的所属组织是 J2Agent AI 平台。
                1. 采用融合检索：收到用户消息后调用 grep_knowledge_repo 检索关键词，同时阅读向量检索（RAG）提供的上下文；将 grep 结果与 RAG 上下文视为互补证据，取并集作答。
                2. grep 有相关内容时，以 grep 原文为主、RAG 上下文补充；围绕用户问题合理呈现，保留关键步骤与图片，避免无脑照搬无关段落。
                3. grep 行级检索未命中不代表无法回答：必须继续阅读 RAG 上下文；若上下文含【来源文件】路径且正文不足，调用 read_knowledge_repo_file 读取完整 Markdown 后再答；可换更短关键词再 grep 最多 1 次，之后必须基于已有证据作答或说明无法回答。
                4. RAG 上下文中的【标题】、【正文】及图像 URL 均为有效答案依据；若正文含图像 URL，须在回答中输出图像的 Markdown。
                5. 禁止提及答案来源于"grep""向量检索""RAG""知识库"等内部机制。
                6. 禁止编造或凭常识补充产品信息；grep 与 RAG 均无相关内容时，礼貌说明无法回答。
                """;
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
            @Qualifier("j2AgentDocsRetriever") AbstractCollectionKbRetriever j2AgentDocsRetriever,
            WebTool webTool,
            KnowledgeRepoMetadataService knowledgeRepoMetadataService,
            KnowledgeMarkdownImageRewriter knowledgeMarkdownImageRewriter,
            ResourceAccessService resourceAccess) {
        this.mathTool = mathTool;
        this.j2AgentDocsRetriever = j2AgentDocsRetriever;
        this.webTool = webTool;
        /**
         * 相对知识库根目录的 Wiki 子目录，需与平台知识库列表中的仓库目录一致。
         */
        this.knowledgeRepoGrepTools = new KnowledgeRepoGrepTools(
                knowledgeRepoMetadataService, "j2agent-docs", knowledgeMarkdownImageRewriter);
        this.knowledgeRepoGrepTools.setPublishMatchedFilesAsSources(true);
        this.knowledgeRepoGrepTools.setResourceAccess(resourceAccess);
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
     * 融合检索：grep 与 RAG 上下文互补，任一侧有可用内容即可作答。
     */
    @Override
    protected QueryAugmenter buildQueryAugmenter() {
        PromptTemplate promptTemplate = new PromptTemplate("""
                以下为向量检索（RAG）提供的参考上下文。
                
                ---------------------
                {context}
                ---------------------
                
                规则：
                1. 【正文】中的图像 URL 须按 Markdown 图片格式输出。
                2. 禁止提及答案来源于"向量检索""RAG""知识库"等内部机制，避免"根据上下文""所提供的资料"等套话。
                
                用户问题：{query}
                
                回答：
                """);
        return ContextualQueryAugmenter.builder()
                .promptTemplate(promptTemplate)
                .allowEmptyContext(true)
                .build();
    }


}
