package io.github.jerryt92.j2agent.plugins.tool;

import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.service.llm.agent.builtin.AgentToolContextSupport;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import io.github.jerryt92.j2agent.service.rag.RagSourcePublicationService;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeMarkdownImageRewriter;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMetadataService;
import io.github.jerryt92.j2agent.service.security.ResourceAccessService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 知识库 Markdown 检索与读取工具：正文行级 grep、文件名匹配、按路径精读原文。
 * 位于 {@code common-tools} 模块，由 Agent 在 {@code buildTools()} 中按知识库子路径构造实例，不作为全局 Spring Bean。
 */
@Slf4j
public class KnowledgeRepoGrepTools {

    private static final int MAX_FILES = 500;
    private static final int MAX_MATCHES = 40;
    private static final int MAX_FILE_BYTES = 512 * 1024;
    private static final int CONTEXT_LINES = 2;
    private static final int FILENAME_PREVIEW_LINES = 30;
    private static final int DEFAULT_READ_MAX_CHARS = 32_000;
    private static final int MIN_PATTERN_LENGTH_FOR_TOKEN_SPLIT = 4;
    private static final String MD_SUFFIX = ".md";

    private final KnowledgeRepoMetadataService metadataService;
    private final String kbRelativeSubPath;
    private final KnowledgeMarkdownImageRewriter imageRewriter;
    private boolean publishMatchedFilesAsSources;
    private ResourceAccessService resourceAccess;

    /**
     * @param metadataService   平台知识库元数据服务（提供 repo 根路径）
     * @param kbRelativeSubPath 相对知识库根的子目录，如 {@code j2agent-docs}；空串表示在 repo 根下搜索
     */
    public KnowledgeRepoGrepTools(KnowledgeRepoMetadataService metadataService, String kbRelativeSubPath) {
        this(metadataService, kbRelativeSubPath, null);
    }

    /**
     * @param imageRewriter 可选，将输出 Markdown 中的相对图片路径改写为 repo 直链（与 RAG 入库一致）
     */
    public KnowledgeRepoGrepTools(KnowledgeRepoMetadataService metadataService, String kbRelativeSubPath,
                                  KnowledgeMarkdownImageRewriter imageRewriter) {
        this.metadataService = metadataService;
        this.kbRelativeSubPath = kbRelativeSubPath == null ? "" : kbRelativeSubPath.replace('\\', '/').trim();
        this.imageRewriter = imageRewriter;
    }

    /**
     * 控制 grep 命中的 Markdown 文件是否进入聊天来源列表；默认关闭以保持旧插件行为。
     */
    public KnowledgeRepoGrepTools setPublishMatchedFilesAsSources(boolean enabled) {
        this.publishMatchedFilesAsSources = enabled;
        return this;
    }

    /** 注入知识库权限服务；未注入时拒绝检索。 */
    public KnowledgeRepoGrepTools setResourceAccess(ResourceAccessService access) {
        this.resourceAccess = access;
        return this;
    }

    /**
     * 在绑定的知识库子目录内对 Markdown 做正文行级与文件名检索；主 pattern 无命中时自动拆词回退。
     */
    public String grepKnowledgeRepo(
            @ToolParam(description = "检索关键词或短语，对 .md 文件正文行与文件名做包含匹配（忽略大小写）") String pattern,
            @ToolParam(description = "可选，在已配置的知识库子目录下再收窄的相对子路径") String relativeSubDir
    ) {
        return grepKnowledgeRepo(pattern, relativeSubDir, null);
    }

    /**
     * 在绑定的知识库子目录内对 Markdown 做正文行级与文件名检索；主 pattern 无命中时自动拆词回退。
     */
    @Tool(name = "grep_knowledge_repo", description = "在知识库 Markdown 目录中按关键词检索：匹配 .md 正文行或文件名，返回命中片段。")
    public String grepKnowledgeRepo(
            @ToolParam(description = "检索关键词或短语，对 .md 文件正文行与文件名做包含匹配（忽略大小写）") String pattern,
            @ToolParam(description = "可选，在已配置的知识库子目录下再收窄的相对子路径") String relativeSubDir,
            ToolContext toolContext
    ) {
        log.info("grep_knowledge_repo 开始: pattern={}, relativeSubDir={}, kbRelativeSubPath={}",
                pattern, relativeSubDir, kbRelativeSubPath);
        if (StringUtils.isBlank(pattern)) {
            log.warn("grep_knowledge_repo 参数无效: pattern 为空");
            return "检索关键词不能为空，请提供 pattern。";
        }
        String denied = denyUnlessReadable(toolContext);
        if (denied != null) {
            return denied;
        }
        Path normalizedRoot = resolveRepoRoot();
        if (normalizedRoot == null) {
            return "知识库根目录未配置或不存在，无法执行 grep。";
        }
        Path searchRoot = resolveSearchRoot(normalizedRoot, relativeSubDir);
        if (searchRoot == null) {
            log.warn("grep_knowledge_repo 检索路径越界: repoRoot={}, relativeSubDir={}", normalizedRoot, relativeSubDir);
            return "检索路径无效或越界，请检查 relativeSubDir。";
        }
        if (!Files.exists(searchRoot) || !Files.isDirectory(searchRoot)) {
            log.warn("grep_knowledge_repo 检索目录不存在: searchRoot={}", searchRoot);
            return "知识库检索目录不存在: " + searchRoot;
        }

        String trimmedPattern = pattern.trim();
        String patternLower = trimmedPattern.toLowerCase(Locale.ROOT);
        List<String> fallbackTokens = splitFallbackTokens(trimmedPattern);

        log.info("grep_knowledge_repo 扫描目录: searchRoot={}", searchRoot);
        long startMs = System.currentTimeMillis();
        List<String> blocks = new ArrayList<>();
        LinkedHashSet<String> matchedSourceFiles = new LinkedHashSet<>();
        int fileCount = 0;
        int matchCount = 0;
        int skippedLargeFiles = 0;

        try {
            List<Path> mdFiles = collectMarkdownFiles(searchRoot);
            List<Path> filenameHits = new ArrayList<>();
            List<Path> otherFiles = new ArrayList<>();
            for (Path file : mdFiles) {
                if (filenameMatches(file.getFileName().toString(), patternLower)) {
                    filenameHits.add(file);
                } else {
                    otherFiles.add(file);
                }
            }

            List<Path> scanOrder = new ArrayList<>(filenameHits);
            scanOrder.addAll(otherFiles);

            for (Path file : scanOrder) {
                if (fileCount >= MAX_FILES || matchCount >= MAX_MATCHES) {
                    break;
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
                boolean filenameHit = filenameHits.contains(file);

                if (filenameHit) {
                    matchCount++;
                    matchedSourceFiles.add(relativeFile);
                    blocks.add(formatFilenameMatchBlock(relativeFile, lines));
                    if (matchCount >= MAX_MATCHES) {
                        break;
                    }
                }

                List<Integer> contentHitLines = findContentHitLines(lines, patternLower, fallbackTokens);
                int fileHits = 0;
                for (int lineIndex : contentHitLines) {
                    if (matchCount >= MAX_MATCHES) {
                        break;
                    }
                    matchCount++;
                    fileHits++;
                    matchedSourceFiles.add(relativeFile);
                    blocks.add(formatMatchBlock(relativeFile, lines, lineIndex));
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
            return "行级检索未命中关键词「" + trimmedPattern + "」（已扫描 " + fileCount + " 个 Markdown 文件）。"
                    + "请结合向量检索上下文作答，或使用 read_knowledge_repo_file 读取【来源文件】中的完整原文；"
                    + "也可换更短关键词重试 grep。";
        }
        log.info("grep_knowledge_repo 完成: pattern={}, scannedMdFiles={}, matchCount={}, skippedLargeFiles={}, " +
                        "hitLimit={}, fileLimit={}, elapsedMs={}",
                pattern, fileCount, matchCount, skippedLargeFiles,
                matchCount >= MAX_MATCHES, fileCount >= MAX_FILES, elapsedMs);
        maybePublishMatchedSourceFiles(toolContext, matchedSourceFiles);
        String header = "共 " + matchCount + " 处命中（最多展示 " + MAX_MATCHES + " 处，扫描文件上限 " + MAX_FILES + "）：\n\n";
        return header + String.join("\n\n---\n\n", blocks);
    }

    private void maybePublishMatchedSourceFiles(ToolContext toolContext, Set<String> matchedSourceFiles) {
        if (!publishMatchedFilesAsSources || matchedSourceFiles == null || matchedSourceFiles.isEmpty()) {
            return;
        }
        publishMatchedSourceFiles(toolContext, matchedSourceFiles);
    }

    protected void publishMatchedSourceFiles(ToolContext toolContext, Set<String> matchedSourceFiles) {
        String conversationId = AgentToolContextSupport.parentConversationId(toolContext);
        if (StringUtils.isBlank(conversationId)) {
            log.debug("grep_knowledge_repo 跳过来源发布: conversationId 为空, files={}", matchedSourceFiles.size());
            return;
        }
        String agentId = AgentToolContextSupport.stringKey(toolContext, AgentRunnableContextKeys.CONTEXT_KEY_AGENT_ID);
        List<Document> documents = matchedSourceFiles.stream()
                .map(sourceFile -> Document.builder()
                        .text("")
                        .metadata(Map.of("sourceFile", sourceFile))
                        .build())
                .toList();
        RagSourcePublicationService.tryPublishFromRetriever(conversationId, agentId, documents);
    }

    /**
     * 按相对知识库根的路径读取 Markdown 原文；grep 未命中且 RAG 上下文含【来源文件】时使用。
     */
    @Tool(name = "read_knowledge_repo_file", description = "按相对知识库根的路径读取 Markdown 原文；grep 未命中且参考上下文含【来源文件】时使用。")
    public String readKnowledgeRepoFile(
            @ToolParam(description = "相对知识库根的文件路径，如 j2agent-docs/xxx/文档.md（与【来源文件】一致）") String relativeFilePath,
            @ToolParam(description = "可选，返回的最大字符数，默认 32000") Integer maxChars,
            ToolContext toolContext
    ) {
        log.info("read_knowledge_repo_file 开始: relativeFilePath={}, maxChars={}", relativeFilePath, maxChars);
        if (StringUtils.isBlank(relativeFilePath)) {
            return "文件路径不能为空，请提供 relativeFilePath。";
        }
        String denied = denyUnlessReadable(toolContext);
        if (denied != null) {
            return denied;
        }
        Path normalizedRoot = resolveRepoRoot();
        if (normalizedRoot == null) {
            return "知识库根目录未配置或不存在，无法读取文件。";
        }
        Path resolvedFile = resolveReadableFile(normalizedRoot, relativeFilePath);
        if (resolvedFile == null) {
            log.warn("read_knowledge_repo_file 路径无效或越界: relativeFilePath={}", relativeFilePath);
            return "文件路径无效或越界，请检查 relativeFilePath（须为 .md 文件且位于已配置的知识库子目录下）。";
        }
        String relative = normalizedRoot.relativize(resolvedFile).toString().replace('\\', '/');
        String sourceDenied = denyUnlessSourceReadable(toolContext, relative);
        if (sourceDenied != null) {
            return sourceDenied;
        }
        if (!Files.exists(resolvedFile) || !Files.isRegularFile(resolvedFile)) {
            log.warn("read_knowledge_repo_file 文件不存在: path={}", resolvedFile);
            return "文件不存在: " + normalizedRoot.relativize(resolvedFile).toString().replace('\\', '/');
        }
        try {
            long size = Files.size(resolvedFile);
            if (size > MAX_FILE_BYTES) {
                return "文件过大（" + size + " 字节），超过读取上限 " + MAX_FILE_BYTES + " 字节。";
            }
            String content = Files.readString(resolvedFile, StandardCharsets.UTF_8);
            int limit = maxChars == null || maxChars <= 0 ? DEFAULT_READ_MAX_CHARS : maxChars;
            content = rewriteOutputMarkdown(relative, content);
            if (content.length() <= limit) {
                return "**文件**: `" + relative + "`\n\n```markdown\n" + content + "\n```";
            }
            String truncated = content.substring(0, limit);
            return "**文件**: `" + relative + "`（已截断，共 " + content.length() + " 字符，展示前 " + limit + " 字符）\n\n"
                    + "```markdown\n" + truncated + "\n```";
        } catch (IOException e) {
            log.warn("read_knowledge_repo_file 读取失败: path={}", resolvedFile, e);
            return "读取文件失败: " + e.getMessage();
        }
    }

    /** 无知识库读权限时拒绝 grep/read，不扫描磁盘。 */
    private String denyUnlessReadable(ToolContext toolContext) {
        if (resourceAccess == null) {
            return "无权访问该知识库。";
        }
        UserContextBo user = AgentToolContextSupport.resolveTurnUser(toolContext);
        if (user == null) {
            return "无权访问该知识库。";
        }
        String repoCode = StringUtils.trimToNull(kbRelativeSubPath);
        if (repoCode == null || repoCode.contains("..") || repoCode.contains("/")) {
            return "无权访问该知识库。";
        }
        try {
            resourceAccess.requireRepository(user, repoCode, 2);
            return null;
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() == HttpStatus.FORBIDDEN
                    || exception.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                return "无权访问该知识库。";
            }
            throw exception;
        }
    }

    /** 按文件相对路径再校验仓库读权限，通过返回 null。 */
    private String denyUnlessSourceReadable(ToolContext toolContext, String relativeSource) {
        String denied = denyUnlessReadable(toolContext);
        if (denied != null) {
            return denied;
        }
        try {
            resourceAccess.requireSource(AgentToolContextSupport.resolveTurnUser(toolContext), relativeSource);
            return null;
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() == HttpStatus.FORBIDDEN
                    || exception.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                return "无权访问该知识库。";
            }
            throw exception;
        }
    }

    private Path resolveRepoRoot() {
        Path repoRoot = metadataService.getRepoRootPath();
        if (repoRoot == null || !Files.exists(repoRoot)) {
            log.warn("grep_knowledge_repo 知识库根目录不可用: repoRoot={}", repoRoot);
            return null;
        }
        return repoRoot.toAbsolutePath().normalize();
    }

    private List<Path> collectMarkdownFiles(Path searchRoot) throws IOException {
        try (Stream<Path> walk = Files.walk(searchRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.endsWith(MD_SUFFIX);
                    })
                    .sorted(Comparator.comparing(path -> searchRoot.relativize(path).toString()))
                    .toList();
        }
    }

    private List<String> splitFallbackTokens(String pattern) {
        if (countCjkChars(pattern) < MIN_PATTERN_LENGTH_FOR_TOKEN_SPLIT) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (int i = 0; i < pattern.length() - 1; i++) {
            if (!isCjkChar(pattern.charAt(i)) || !isCjkChar(pattern.charAt(i + 1))) {
                continue;
            }
            tokens.add(pattern.substring(i, i + 2).toLowerCase(Locale.ROOT));
        }
        return tokens.isEmpty() ? List.of() : List.copyOf(tokens);
    }

    private boolean filenameMatches(String fileName, String patternLower) {
        return fileName.toLowerCase(Locale.ROOT).contains(patternLower);
    }

    private static int countCjkChars(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (isCjkChar(value.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    private static boolean isCjkChar(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private List<Integer> findContentHitLines(List<String> lines, String patternLower,
                                              List<String> fallbackTokens) {
        List<Integer> primaryHits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).toLowerCase(Locale.ROOT).contains(patternLower)) {
                primaryHits.add(i);
            }
        }
        if (!primaryHits.isEmpty()) {
            return primaryHits;
        }
        if (fallbackTokens.isEmpty()) {
            return List.of();
        }
        List<Integer> tokenHits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String lineLower = lines.get(i).toLowerCase(Locale.ROOT);
            for (String token : fallbackTokens) {
                if (lineLower.contains(token)) {
                    tokenHits.add(i);
                    break;
                }
            }
        }
        return tokenHits;
    }

    private Path resolveSearchRoot(Path normalizedRoot, String relativeSubDir) {
        Path base = resolveKbBase(normalizedRoot);
        if (base == null) {
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

    private Path resolveReadableFile(Path normalizedRoot, String relativeFilePath) {
        Path base = resolveKbBase(normalizedRoot);
        if (base == null) {
            return null;
        }
        String normalized = relativeFilePath.replace('\\', '/').trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        Path resolved = normalizedRoot.resolve(normalized).normalize();
        if (!resolved.startsWith(base)) {
            return null;
        }
        if (!resolved.getFileName().toString().endsWith(MD_SUFFIX)) {
            return null;
        }
        return resolved;
    }

    private Path resolveKbBase(Path normalizedRoot) {
        Path base = StringUtils.isBlank(kbRelativeSubPath)
                ? normalizedRoot
                : normalizedRoot.resolve(kbRelativeSubPath).normalize();
        if (!base.startsWith(normalizedRoot)) {
            log.warn("知识库子路径越界: {}", kbRelativeSubPath);
            return null;
        }
        return base;
    }

    private String rewriteOutputMarkdown(String relativeFile, String markdown) {
        if (imageRewriter == null || StringUtils.isBlank(markdown)) {
            return markdown;
        }
        return imageRewriter.rewriteTextChunkImages(relativeFile, markdown);
    }

    private String formatFilenameMatchBlock(String relativeFile, List<String> lines) {
        int to = Math.min(lines.size(), FILENAME_PREVIEW_LINES);
        List<String> previewLines = lines.subList(0, to);
        String rewrittenPreview = rewriteOutputMarkdown(relativeFile, String.join("\n", previewLines));
        String[] rewrittenLines = rewrittenPreview.split("\n", -1);

        StringBuilder sb = new StringBuilder();
        sb.append("**文件**: `").append(relativeFile).append("`\n");
        sb.append("**命中方式**: 文件名匹配\n");
        sb.append("**预览** (前 ").append(FILENAME_PREVIEW_LINES).append(" 行):\n```\n");
        for (int i = 0; i < rewrittenLines.length; i++) {
            sb.append(i + 1).append(": ").append(rewrittenLines[i]).append('\n');
        }
        sb.append("```\n");
        sb.append("可调用 read_knowledge_repo_file 读取完整原文。");
        return sb.toString();
    }

    private String formatMatchBlock(String relativeFile, List<String> lines, int matchLineIndex) {
        int from = Math.max(0, matchLineIndex - CONTEXT_LINES);
        int to = Math.min(lines.size() - 1, matchLineIndex + CONTEXT_LINES);
        List<String> contextLines = lines.subList(from, to + 1);
        String rewrittenContext = rewriteOutputMarkdown(relativeFile, String.join("\n", contextLines));
        String[] rewrittenLines = rewrittenContext.split("\n", -1);

        StringBuilder sb = new StringBuilder();
        sb.append("**文件**: `").append(relativeFile).append("`\n");
        sb.append("**命中行**: ").append(matchLineIndex + 1).append("\n```\n");
        for (int i = 0; i < rewrittenLines.length; i++) {
            int lineNum = from + i + 1;
            String prefix = (lineNum - 1 == matchLineIndex) ? ">> " : "   ";
            sb.append(prefix).append(lineNum).append(": ").append(rewrittenLines[i]).append('\n');
        }
        sb.append("```");
        return sb.toString();
    }
}
