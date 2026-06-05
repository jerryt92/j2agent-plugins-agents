# Agent 开发

本文说明如何继承平台基类 `AiAgent`、实现插件 Agent，并完成部署与热重载。

## 1. 核心契约

`AiAgent` 位于 `io.github.jerryt92.j2agent.service.llm.agent.AiAgent`，基于 **Spring AI Alibaba `ReactAgent`** 封装对话、记忆、工具、Skill、RAG 等能力。

### 1.1 必须实现的抽象方法

| 方法 | 说明 |
|------|------|
| `getAgentId()` | 全局唯一标识；与 WebSocket `agent-id`、DB/Redis 中 `agent_id` 一致 |
| `getAgentName()` | 智能体展示名称（`GET /agents` 列表） |
| `getAgentDescription()` | 智能体描述文案 |
| `loadSystemPrompt()` | 系统提示词；可从 classpath 读取或直接返回字符串 |

### 1.2 可选 override

| 方法 | 默认 | 说明 |
|------|------|------|
| `getSort()` | `100` | 业务排序权重（当前列表 API 按 `agentId` 字典序，未消费此字段） |
| `getThinkingOverride()` | `USE_PROVIDER_DEFAULT` | Agent 级深度思考默认策略；见 [可选能力.md](../../j2agent/docs/插件Agent接入与界面/agent开发/可选能力.md) |
| `isQaTemplateEnabled()` | `false` | 是否启用热门问题模板 |
| `buildTools()` | 空数组 | 挂载 `@Tool` 工具 Bean |
| `buildSkillNames()` | 空集合 | 启用 Skill 的 id 列表 |
| `buildDocumentRetriever()` | `null` | RAG 检索器 |
| `buildToolCallbacks()` | 由 `buildTools()` 转换 | 需合并 MCP 时 override |
| `buildInterceptors()` | 工具 UI + Skill UI 拦截器 | 可扩展；工具异常兜底始终保留 |

## 2. 最小示例

```java
package com.nms.prodplugin.ai.center.demo;

import io.github.jerryt92.j2agent.service.llm.agent.AiAgent;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Component
public class DemoAgent extends AiAgent {

    @Override
    public String getAgentId() {
        return "demo_agent";
    }

    @Override
    public String getAgentName() {
        return "演示 Agent";
    }

    @Override
    public String getAgentDescription() {
        return "最小接入示例，用于验证插件加载与对话链路。";
    }

    @Override
    public String loadSystemPrompt() {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("system-prompt.md")) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // fallback
        }
        return "你是演示助手，回答应简洁准确。";
    }
}
```

`src/main/resources/system-prompt.md`（可选）：

```markdown
你是演示助手。
- 回答使用中文。
- 不确定时明确说明，不要编造。
```

## 3. 生命周期

```mermaid
sequenceDiagram
  participant Spring
  participant DemoAgent
  participant ReactAgent
  participant Router as AgentRouter

  Spring->>DemoAgent: @PostConstruct initAgent()
  DemoAgent->>DemoAgent: rebuildAgent()
  DemoAgent->>DemoAgent: buildAgent()
  Note over DemoAgent: ChatClient + tools + skills + interceptors
  DemoAgent->>ReactAgent: builder.build()
  Spring->>Router: refresh() 聚合 List AiAgent
```

1. **Bean 实例化**：`AgentPluginRegistry` 在 `ApplicationReadyEvent` 后先实例化插件内依赖 Bean，再实例化 `AiAgent`（保证工具类等已就绪）。
2. **`@PostConstruct`**：调用 `rebuildAgent()` → `buildAgent()` 组装底层 `ReactAgent`。
3. **路由注册**：`AgentRouter.refresh()` 将所有 `AiAgent` Bean 按 `getAgentId()` 放入 Map；重复 id 抛 `IllegalStateException`。
4. **MCP 变更**：监听 `McpToolCallbacksRefreshedEvent`，全部 Agent 执行 `rebuildAgent()`（仅对 override 了 MCP 合并的 Agent 生效）。

对话入口：`ChatService` → `agentRouter.route(agentId)` → `AiAgent.stream(AgentRunContext)`。

## 4. 插件约束

### 4.1 包与类加载

| 约束 | 说明 |
|------|------|
| 包名 | **`com.nms.prodplugin.ai.center`** 及子包 |
| 平台类 | `io.github.jerryt92.j2agent.*` 由 `PluginAgentClassLoader` **委托父 ClassLoader** 加载 |
| 禁止重复打包 | 插件 JAR **不得**包含平台类，否则可能类加载冲突 |
| 依赖 Bean | 工具、Retriever 等同 JAR 类须带 Spring 注解且在同一扫描包下 |

### 4.2 agentId 唯一性

- 插件 Agent 之间、插件与内置 Agent 之间 **`getAgentId()` 不可重复**。
- 启动与 `POST /agents/reload` 均会校验；冲突时整次 reload 失败。

### 4.3 历史别名（可选）

若需兼容旧客户端字符串，在平台侧 `AgentRouter#route` 增加映射（如已有 `assistant` → `chat_assistant`）。新 Agent 建议使用稳定的新 id，避免依赖别名。

## 5. 部署与热重载

### 5.0 工程模型：一 Agent 一独立工程

| 坐标 | 是否必须继承 | 说明 |
|------|-------------|------|
| `j2agent-plugins-agents`（`agents/pom.xml`） | **否** | 本仓库示例 Agent 聚合，便于一键 `mvn package`；**外部工程勿继承** |

原则：每个 Agent 是**独立 Maven 工程**（单独 `pom.xml`、可单独 `mvn package`）；放入本仓库 `agents/` 仅为示例集中管理，**不改变**「一 Agent 一工程」模型。**复制 [`0_example-agent`](../agents/0_example-agent/)** 即可开始开发。

本仓库布局：

```text
j2agent-plugins-agents/
  agents/
    pom.xml                             # 示例聚合（可选，勿对外继承）
    0_example-agent/                    # ★ 最小模板，复制此目录
    inc-qa-assistant/                   # 业务示例 Agent
    inc-intelligent-report/
    rc-wiki-assistant/
```

工程骨架见 [快速入门](../../j2agent/docs/插件Agent接入与界面/agent开发/README.md#2-最小工程骨架)；打包配置见 [README.md](../README.md)。

### 5.1 打包

**方式 A：单 Agent 独立打包**（推荐 CI 按 Agent 拆分）：

```bash
cd j2agent-plugins-agents/agents/inc-qa-assistant && mvn -q clean package
```

**方式 B：本仓库一键编译全部示例**：

```bash
cd j2agent-plugins-agents/agents && mvn clean package
```

`mvn package` 后在 **`target/`** 同时生成解压目录与 tar.gz（内容一致）。目录结构：

```text
target/
  <artifactId>-<version>.jar        # 瘦 JAR（仅 class）
  <artifactId>-<version>/             # 解压目录（扁平）
    <artifactId>-<version>.jar
    resources/                        # 与 src/main/resources 一致
  <artifactId>-<version>.tar.gz       # 便于分发（内容与上者相同）
```

`loadSystemPrompt()`、`qa-template.json`、Skill 正文均从 **`resources/`** 目录加载（不在 JAR 内）。修改 Skill 或提示词文件后，执行 **`POST /agents/reload`** 即可生效，无需重打 JAR。

### 5.2 配置

```yaml
com:
  nms:
    ai:
      plugin:
        path: ${user.home}/j2agent/plugins/agents
```

### 5.3 部署步骤

```bash
PLUGIN_PATH="${user.home}/j2agent/plugins/agents"   # 或生产 /opt/j2agent/volume/plugins/agents

# 方式 A：直接使用 target 下解压目录
cp -a target/inc-intelligent-report-1.0.0-SNAPSHOT/. \
  "$PLUGIN_PATH/inc-intelligent-report-1.0.0-SNAPSHOT/"

# 方式 B：分发 tar.gz（先建目标目录再解压，避免多一层嵌套）
mkdir -p "$PLUGIN_PATH/inc-intelligent-report-1.0.0-SNAPSHOT"
tar -xzf target/inc-intelligent-report-1.0.0-SNAPSHOT.tar.gz \
  -C "$PLUGIN_PATH/inc-intelligent-report-1.0.0-SNAPSHOT"
```

每个 Agent 在 `plugin.path` 下占**一级子目录**（多 Agent = 多个子目录并存；每目录内须有一个 `.jar` 与 `resources/`）。tar.gz 仅便于分发，内容与 `target/<artifactId>-<version>/` 相同。

```text
plugin.path/
  inc-qa-assistant-1.0.0-SNAPSHOT/
    inc-qa-assistant-1.0.0-SNAPSHOT.jar
    resources/...
  inc-intelligent-report-1.0.0-SNAPSHOT/
    ...
```

**兼容旧方式**：仍可将单个 JAR 直接放在 `plugin.path` 根目录（资源须在 JAR 内，不推荐新 Agent 使用）。

### 5.4 管理 API（需 ADMIN 角色）

| 接口 | 说明 |
|------|------|
| `GET /v1/rest/j2agent/plugins/agents` | 返回插件 JAR 路径列表（含子目录相对路径，如 `inc-qa-assistant-1.0.0-SNAPSHOT/inc-qa-assistant-1.0.0-SNAPSHOT.jar`）、已加载 `agentId` |
| `POST /v1/rest/j2agent/agents/reload` | 重新扫描目录并注册 Agent |

典型日志关键字：

- `Loading plugin bundle: ...`
- `Registered dynamic plugin bean definition`
- `Loaded plugin agent: demo_agent`

### 5.5 验证

见 [快速入门验证清单](../../j2agent/docs/插件Agent接入与界面/agent开发/README.md#验证清单)。

## 6. 平台代码索引

| 主题 | 路径 |
|------|------|
| Agent 基类 | `j2agent-server/.../service/llm/agent/AiAgent.java` |
| 插件注册 | `.../service/llm/agent/AgentPluginRegistry.java` |
| 插件 bundle 发现 | `.../service/llm/agent/AgentPluginBundle.java` |
| 类加载器 | `.../service/llm/agent/PluginAgentClassLoader.java` |
| 路由 | `.../service/llm/agent/AgentRouter.java` |
| MCP 重建监听 | `.../service/llm/agent/McpToolCallbacksRefreshedListener.java` |
| 对话编排 | `.../service/llm/ChatService.java` |

## 7. 相关文档

- [快速入门](../../j2agent/docs/插件Agent接入与界面/agent开发/README.md) — 工程骨架与验证清单
- [工具.md](../../j2agent/docs/插件Agent接入与界面/agent开发/工具.md) — 挂载 Tool
- [Skill.md](../../j2agent/docs/插件Agent接入与界面/agent开发/Skill.md) — 挂载 Skill
- [MCP.md](../../j2agent/docs/插件Agent接入与界面/agent开发/MCP.md) — 挂载 MCP
- [可选能力.md](../../j2agent/docs/插件Agent接入与界面/agent开发/可选能力.md) — RAG、热门问题、深度思考
- [插件智能体接入与界面](../../j2agent/docs/插件Agent接入与界面/README.md) — 前端暴露链路
