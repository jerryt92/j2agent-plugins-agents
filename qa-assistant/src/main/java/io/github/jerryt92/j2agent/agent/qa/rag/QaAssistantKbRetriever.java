package io.github.jerryt92.j2agent.agent.qa.rag;

import io.github.jerryt92.j2agent.rag.AbstractCollectionKbRetriever;
import io.github.jerryt92.j2agent.service.rag.retrieval.Retriever;
import org.springframework.stereotype.Component;

/**
 * <p>与 {@link io.github.jerryt92.j2agent.controller.KnowledgeController#retrieveKnowledge} 对照调试时，请传入相同 collection，并注意 {@link Retriever#retrieveKnowledge}
 * 与 {@link Retriever#retrieveRagChunks} 的结果集差异（日志中已标注）。</p>
 */
@Component("QaAssistantKbRetriever")
public class QaAssistantKbRetriever extends AbstractCollectionKbRetriever {

    /**
     * 注入通用检索引擎，固定绑定 {@link #boundCollection()}。
     */
    protected QaAssistantKbRetriever(Retriever retriever) {
        super(retriever);
    }

    /**
     * 返回本检索器绑定的 Milvus collection 名称。
     */
    @Override
    protected String boundCollection() {
        return "knowledge_base";
    }
}
