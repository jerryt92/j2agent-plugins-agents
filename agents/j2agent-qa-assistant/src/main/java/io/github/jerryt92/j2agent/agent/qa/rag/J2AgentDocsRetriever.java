package io.github.jerryt92.j2agent.agent.qa.rag;

import io.github.jerryt92.j2agent.service.rag.inf.AbstractCollectionKbRetriever;
import io.github.jerryt92.j2agent.service.rag.retrieval.Retriever;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <p>与 {@link io.github.jerryt92.j2agent.controller.KnowledgeController#retrieveKnowledge} 对照调试时，请传入相同 collection，并注意 {@link Retriever#retrieveKnowledge}
 * 与 {@link Retriever#retrieveRagChunks} 的结果集差异（日志中已标注）。</p>
 */
@Component("j2AgentDocsRetriever")
public class J2AgentDocsRetriever extends AbstractCollectionKbRetriever {

    /**
     * 注入通用检索引擎，固定绑定 {@link #boundCollections()}。
     */
    protected J2AgentDocsRetriever(Retriever retriever) {
        super(retriever);
    }

    /**
     * 返回本检索器绑定的 Milvus collection 名称列表。
     */
    @Override
    protected List<String> boundCollections() {
        return List.of("j2agent_docs");
    }
}
