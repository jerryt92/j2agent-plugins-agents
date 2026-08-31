package io.github.jerryt92.j2agent.plugins.tool;

import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeMarkdownImageRewriter;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeRepoGrepToolsTest {

    @TempDir
    Path tempDir;

    private KnowledgeRepoGrepTools tools;

    @BeforeEach
    void setUp() throws Exception {
        KnowledgeRepoMetadataService metadataService = new KnowledgeRepoMetadataService(new KnowledgeRepoProperties(), null, null);
        setRepoRootPath(metadataService, tempDir);
        tools = new KnowledgeRepoGrepTools(metadataService, "j2agent-docs");
    }

    @Test
    void grep_contentMatch_returnsHitBlock() throws IOException {
        Path docsDir = tempDir.resolve("j2agent-docs");
        Files.createDirectories(docsDir);
        Path md = docsDir.resolve("guide.md");
        Files.writeString(md, """
                # 设备说明
                共享输出设备需完成身份验证后方可使用。
                """, StandardCharsets.UTF_8);

        String result = tools.grepKnowledgeRepo("共享输出设备", "");

        assertTrue(result.contains("命中"));
        assertTrue(result.contains("guide.md"));
        assertTrue(result.contains("共享输出设备需完成身份验证后方可使用"));
    }

    @Test
    void grep_filenameMatch_whenContentHasNoKeyword() throws IOException {
        Path docsDir = tempDir.resolve("j2agent-docs");
        Files.createDirectories(docsDir);
        Path md = docsDir.resolve("device-setup-manual.md");
        Files.writeString(md, """
                ![](http://example.com/diagram.png)
                """, StandardCharsets.UTF_8);

        String result = tools.grepKnowledgeRepo("device-setup-manual", "");

        assertTrue(result.contains("文件名匹配"));
        assertTrue(result.contains("device-setup-manual.md"));
        assertTrue(result.contains("read_knowledge_repo_file"));
    }

    @Test
    void grep_tokenFallback_whenFullPatternMissing() throws IOException {
        Path docsDir = tempDir.resolve("j2agent-docs");
        Files.createDirectories(docsDir);
        Path md = docsDir.resolve("office.md");
        Files.writeString(md, """
                三楼设备间配置了一台共享终端，东区用户登录后即可操作。
                """, StandardCharsets.UTF_8);

        String result = tools.grepKnowledgeRepo("东区三楼共享设备", "");

        assertTrue(result.contains("命中"));
        assertTrue(result.contains("共享终端") || result.contains("东区"));
    }

    @Test
    void grep_noMatch_returnsSoftMessage() throws IOException {
        Path docsDir = tempDir.resolve("j2agent-docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("fixture-a.md"), "占位文本，无检索价值。\n", StandardCharsets.UTF_8);

        String result = tools.grepKnowledgeRepo("zzzz-not-found-term", "");

        assertTrue(result.contains("行级检索未命中"));
        assertTrue(result.contains("向量检索上下文"));
        assertTrue(result.contains("read_knowledge_repo_file"));
        assertFalse(result.contains("未在知识库目录中找到"));
    }

    @Test
    void grep_defaultDoesNotPublishMatchedSources() throws Exception {
        CapturingKnowledgeRepoGrepTools capturingTools = createCapturingTools();
        Path docsDir = tempDir.resolve("j2agent-docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("guide.md"), "共享输出设备\n", StandardCharsets.UTF_8);

        String result = capturingTools.grepKnowledgeRepo("共享输出设备", "");

        assertTrue(result.contains("命中"));
        assertTrue(capturingTools.publishedSourceFiles.isEmpty());
    }

    @Test
    void grep_setterEnabledPublishesFilenameAndContentHits() throws Exception {
        CapturingKnowledgeRepoGrepTools capturingTools = createCapturingTools();
        capturingTools.setPublishMatchedFilesAsSources(true);
        Path docsDir = tempDir.resolve("j2agent-docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("共享输出.md"), "没有正文关键词。\n", StandardCharsets.UTF_8);
        Files.writeString(docsDir.resolve("guide.md"), "共享输出设备需认证后使用。\n", StandardCharsets.UTF_8);

        String result = capturingTools.grepKnowledgeRepo("共享输出", "");

        assertTrue(result.contains("共享输出.md"));
        assertTrue(result.contains("guide.md"));
        assertTrue(capturingTools.publishedSourceFiles.contains("j2agent-docs/共享输出.md"));
        assertTrue(capturingTools.publishedSourceFiles.contains("j2agent-docs/guide.md"));
    }

    @Test
    void grep_publishesSameFileOnlyOnceWhenMultipleLinesHit() throws Exception {
        CapturingKnowledgeRepoGrepTools capturingTools = createCapturingTools();
        capturingTools.setPublishMatchedFilesAsSources(true);
        Path docsDir = tempDir.resolve("j2agent-docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("guide.md"), """
                共享输出设备第一行。
                共享输出设备第二行。
                """, StandardCharsets.UTF_8);

        String result = capturingTools.grepKnowledgeRepo("共享输出设备", "");

        assertTrue(result.contains("命中"));
        assertTrue(capturingTools.publishedSourceFiles.contains("j2agent-docs/guide.md"));
        assertTrue(capturingTools.publishedSourceFiles.size() == 1);
    }

    @Test
    void grep_enabledDoesNotPublishWhenNoMatch() throws Exception {
        CapturingKnowledgeRepoGrepTools capturingTools = createCapturingTools();
        capturingTools.setPublishMatchedFilesAsSources(true);
        Path docsDir = tempDir.resolve("j2agent-docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("guide.md"), "普通内容。\n", StandardCharsets.UTF_8);

        String result = capturingTools.grepKnowledgeRepo("zzzz-not-found-term", "");

        assertTrue(result.contains("行级检索未命中"));
        assertTrue(capturingTools.publishedSourceFiles.isEmpty());
    }

    @Test
    void read_readsMarkdownUnderDocs() throws IOException {
        Path docsDir = tempDir.resolve("j2agent-docs").resolve("platform");
        Files.createDirectories(docsDir);
        Path md = docsDir.resolve("manual.md");
        String content = "### 操作步骤\n1. 执行第一步\n2. 执行第二步\n";
        Files.writeString(md, content, StandardCharsets.UTF_8);

        String result = tools.readKnowledgeRepoFile("j2agent-docs/platform/manual.md", null);

        assertTrue(result.contains("j2agent-docs/platform/manual.md"));
        assertTrue(result.contains("操作步骤"));
        assertTrue(result.contains("执行第一步"));
    }

    @Test
    void read_rejectsPathOutsideDocs() {
        String result = tools.readKnowledgeRepoFile("../secret.md", null);

        assertTrue(result.contains("无效或越界"));
    }

    @Test
    void read_rejectsNonMarkdown() {
        String result = tools.readKnowledgeRepoFile("j2agent-docs/readme.txt", null);

        assertTrue(result.contains("无效或越界"));
    }

    @Test
    void grep_rewritesRelativeImageUrls_whenRewriterProvided() throws Exception {
        KnowledgeRepoGrepTools rewritingTools = createToolsWithImageRewriter();
        Path docsDir = tempDir.resolve("j2agent-docs").resolve("product");
        Files.createDirectories(docsDir);
        Path md = docsDir.resolve("faq.md");
        Files.writeString(md, """
                # 登录说明
                登录页如下：
                ![登录页](./images/登录 页.png)
                """, StandardCharsets.UTF_8);

        String result = rewritingTools.grepKnowledgeRepo("登录页", "");

        assertTrue(result.contains("/file/repo/"));
        assertTrue(result.contains("j2agent-docs/product/images/%E7%99%BB%E5%BD%95+%E9%A1%B5.png"));
        assertFalse(result.contains("./images/登录 页.png"));
    }

    @Test
    void read_rewritesRelativeImageUrls_whenRewriterProvided() throws Exception {
        KnowledgeRepoGrepTools rewritingTools = createToolsWithImageRewriter();
        Path docsDir = tempDir.resolve("j2agent-docs").resolve("product");
        Files.createDirectories(docsDir);
        Path md = docsDir.resolve("faq.md");
        Files.writeString(md, """
                ![登录页](./images/登录 页.png)
                """, StandardCharsets.UTF_8);

        String result = rewritingTools.readKnowledgeRepoFile("j2agent-docs/product/faq.md", null);

        assertTrue(result.contains("/file/repo/j2agent-docs/product/images/%E7%99%BB%E5%BD%95+%E9%A1%B5.png"));
        assertFalse(result.contains("./images/登录 页.png"));
    }

    @Test
    void read_keepsRelativeImageUrls_whenRewriterNotProvided() throws IOException {
        Path docsDir = tempDir.resolve("j2agent-docs").resolve("product");
        Files.createDirectories(docsDir);
        Path md = docsDir.resolve("faq.md");
        Files.writeString(md, "![登录页](./images/登录 页.png)\n", StandardCharsets.UTF_8);

        String result = tools.readKnowledgeRepoFile("j2agent-docs/product/faq.md", null);

        assertTrue(result.contains("./images/登录 页.png"));
        assertFalse(result.contains("/file/repo/"));
    }

    private KnowledgeRepoGrepTools createToolsWithImageRewriter() throws Exception {
        KnowledgeRepoMetadataService metadataService = new KnowledgeRepoMetadataService(new KnowledgeRepoProperties(), null, null);
        setRepoRootPath(metadataService, tempDir);
        return new KnowledgeRepoGrepTools(metadataService, "j2agent-docs", new KnowledgeMarkdownImageRewriter());
    }

    private CapturingKnowledgeRepoGrepTools createCapturingTools() throws Exception {
        KnowledgeRepoMetadataService metadataService = new KnowledgeRepoMetadataService(new KnowledgeRepoProperties(), null, null);
        setRepoRootPath(metadataService, tempDir);
        return new CapturingKnowledgeRepoGrepTools(metadataService);
    }

    private static void setRepoRootPath(KnowledgeRepoMetadataService metadataService, Path root) throws Exception {
        Field field = KnowledgeRepoMetadataService.class.getDeclaredField("repoRootPath");
        field.setAccessible(true);
        field.set(metadataService, root);
    }

    private static final class CapturingKnowledgeRepoGrepTools extends KnowledgeRepoGrepTools {

        final List<String> publishedSourceFiles = new ArrayList<>();

        CapturingKnowledgeRepoGrepTools(KnowledgeRepoMetadataService metadataService) {
            super(metadataService, "j2agent-docs");
        }

        @Override
        protected void publishMatchedSourceFiles(ToolContext toolContext, Set<String> matchedSourceFiles) {
            publishedSourceFiles.addAll(matchedSourceFiles);
        }
    }
}
