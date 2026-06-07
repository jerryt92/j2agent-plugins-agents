package io.github.jerryt92.j2agent.agent.qa.tool;

import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMetadataService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 知识库 Markdown 原文 Grep 工具：在指定相对子目录下对 .md 做行级检索，供 RAG 未命中时回退使用。
 * 由 Agent 在 {@code buildTools()} 中按知识库子路径构造实例，不作为全局 Spring Bean。
 */
@Slf4j
public class KnowledgeRepoGrepTools {

    private static final int MAX_FILES = 500;
    private static final int MAX_MATCHES = 40;
    private static final int MAX_FILE_BYTES = 512 * 1024;
    private static final int CONTEXT_LINES = 2;
    private static final String MD_SUFFIX = ".md";

    private final KnowledgeRepoMetadataService metadataService;
    private final String kbRelativeSubPath;

    /**
     * @param metadataService   平台知识库元数据服务（提供 repo 根路径）
     * @param kbRelativeSubPath 相对知识库根的子目录，如 {@code wiki}；空串表示在 repo 根下搜索
     */
    public KnowledgeRepoGrepTools(KnowledgeRepoMetadataService metadataService, String kbRelativeSubPath) {
        this.metadataService = metadataService;
        this.kbRelativeSubPath = kbRelativeSubPath == null ? "" : kbRelativeSubPath.replace('\\', '/').trim();
    }

    /**
     * 在绑定的知识库子目录内对 Markdown 文件做行级检索。
     */
    @Tool(name = "grep_knowledge_repo", description = "在知识库 Markdown 目录中按关键词行级检索产品事实原文。")
    public String grepKnowledgeRepo(
            @ToolParam(description = "检索关键词或短语，对 .md 文件内容做包含匹配（忽略大小写）") String pattern,
            @ToolParam(description = "可选，在已配置的知识库子目录下再收窄的相对子路径") String relativeSubDir
    ) {
        log.info("grep_knowledge_repo 开始: pattern={}, relativeSubDir={}, kbRelativeSubPath={}",
                pattern, relativeSubDir, kbRelativeSubPath);
        if (StringUtils.isBlank(pattern)) {
            log.warn("grep_knowledge_repo 参数无效: pattern 为空");
            return "检索关键词不能为空，请提供 pattern。";
        }
        Path repoRoot = metadataService.getRepoRootPath();
        if (repoRoot == null || !Files.exists(repoRoot)) {
            log.warn("grep_knowledge_repo 知识库根目录不可用: repoRoot={}", repoRoot);
            return "知识库根目录未配置或不存在，无法执行 grep。";
        }
        Path normalizedRoot = repoRoot.toAbsolutePath().normalize();
        Path searchRoot = resolveSearchRoot(normalizedRoot, relativeSubDir);
        if (searchRoot == null) {
            log.warn("grep_knowledge_repo 检索路径越界: repoRoot={}, relativeSubDir={}", normalizedRoot, relativeSubDir);
            return "检索路径无效或越界，请检查 relativeSubDir。";
        }
        if (!Files.exists(searchRoot) || !Files.isDirectory(searchRoot)) {
            log.warn("grep_knowledge_repo 检索目录不存在: searchRoot={}", searchRoot);
            return "知识库检索目录不存在: " + searchRoot;
        }

        log.info("grep_knowledge_repo 扫描目录: searchRoot={}", searchRoot);
        long startMs = System.currentTimeMillis();
        String patternLower = pattern.trim().toLowerCase(Locale.ROOT);
        List<String> blocks = new ArrayList<>();
        int fileCount = 0;
        int matchCount = 0;
        int skippedLargeFiles = 0;

        try (Stream<Path> walk = Files.walk(searchRoot)) {
            var fileIterator = walk.filter(Files::isRegularFile).iterator();
            while (fileIterator.hasNext() && fileCount < MAX_FILES && matchCount < MAX_MATCHES) {
                Path file = fileIterator.next();
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(MD_SUFFIX) || "info.json".equals(fileName)) {
                    continue;
                }
                fileCount++;
                long size = Files.size(file);
                if (size > MAX_FILE_BYTES) {
                    skippedLargeFiles++;
                    log.debug("grep_knowledge_repo 跳过大文件: path={}, size={}", file, size);
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                String relativeFile = normalizedRoot.relativize(file).toString().replace('\\', '/');
                int fileHits = 0;
                for (int i = 0; i < lines.size() && matchCount < MAX_MATCHES; i++) {
                    if (!lines.get(i).toLowerCase(Locale.ROOT).contains(patternLower)) {
                        continue;
                    }
                    matchCount++;
                    fileHits++;
                    blocks.add(formatMatchBlock(relativeFile, lines, i));
                }
                if (fileHits > 0) {
                    log.debug("grep_knowledge_repo 文件命中: file={}, hits={}", relativeFile, fileHits);
                }
            }
        } catch (IOException e) {
            log.warn("grep_knowledge_repo 扫描异常: searchRoot={}, pattern={}", searchRoot, pattern, e);
            return "知识库检索失败: " + e.getMessage();
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        if (blocks.isEmpty()) {
            log.info("grep_knowledge_repo 无命中: pattern={}, scannedMdFiles={}, skippedLargeFiles={}, elapsedMs={}",
                    pattern, fileCount, skippedLargeFiles, elapsedMs);
            return "未在知识库目录中找到包含「" + pattern + "」的 Markdown 行，可尝试更短或同义关键词后重试。";
        }
        log.info("grep_knowledge_repo 完成: pattern={}, scannedMdFiles={}, matchCount={}, skippedLargeFiles={}, " +
                        "hitLimit={}, fileLimit={}, elapsedMs={}",
                pattern, fileCount, matchCount, skippedLargeFiles,
                matchCount >= MAX_MATCHES, fileCount >= MAX_FILES, elapsedMs);
        String header = "共 " + matchCount + " 处命中（最多展示 " + MAX_MATCHES + " 处，扫描文件上限 " + MAX_FILES + "）：\n\n";
        return header + String.join("\n\n---\n\n", blocks);
    }

    /**
     * 解析并校验检索根目录：repoRoot + kbRelativeSubPath + optional relativeSubDir。
     */
    private Path resolveSearchRoot(Path normalizedRoot, String relativeSubDir) {
        Path base = StringUtils.isBlank(kbRelativeSubPath)
                ? normalizedRoot
                : normalizedRoot.resolve(kbRelativeSubPath).normalize();
        if (!base.startsWith(normalizedRoot)) {
            log.warn("知识库子路径越界: {}", kbRelativeSubPath);
            return null;
        }
        if (StringUtils.isBlank(relativeSubDir)) {
            return base;
        }
        String sub = relativeSubDir.replace('\\', '/').trim();
        if (sub.startsWith("/")) {
            sub = sub.substring(1);
        }
        Path resolved = base.resolve(sub).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            log.warn("相对子目录越界: {}", relativeSubDir);
            return null;
        }
        return resolved;
    }

    /**
     * 格式化单处命中：文件路径、行号与上下文行。
     */
    private String formatMatchBlock(String relativeFile, List<String> lines, int matchLineIndex) {
        int from = Math.max(0, matchLineIndex - CONTEXT_LINES);
        int to = Math.min(lines.size() - 1, matchLineIndex + CONTEXT_LINES);
        StringBuilder sb = new StringBuilder();
        sb.append("**文件**: `").append(relativeFile).append("`\n");
        sb.append("**命中行**: ").append(matchLineIndex + 1).append("\n```\n");
        for (int i = from; i <= to; i++) {
            String prefix = (i == matchLineIndex) ? ">> " : "   ";
            sb.append(prefix).append(i + 1).append(": ").append(lines.get(i)).append('\n');
        }
        sb.append("```");
        return sb.toString();
    }
}
