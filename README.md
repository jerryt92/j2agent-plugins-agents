# j2agent-plugins-agents

j2agent Agent 插件示例仓库：每个 Agent 为**独立 Maven 工程**；`agents/pom.xml` 仅用于本仓库一键编译，**不必继承**。

## 仓库结构

```text
j2agent-plugins-agents/
  agents/
    pom.xml                         # 本仓库聚合 POM（一键编译，勿对外继承）
    0_example-agent/                # ★ 最小模板，复制此目录开始开发
    qa-assistant/                   # 业务示例 Agent
    j2agent-qa-assistant/
```

## 依赖版本（BOM Parent）

第三方依赖版本由 [`j2agent-bom`](../j2agent/j2agent-bom/) 作为 **parent** 继承管理；`io.github.jerryt92.j2agent` 平台构件版本由 `j2agent.version` 属性单独声明（与 BOM 版本对齐），**不在 BOM 的 dependencyManagement 内**。构建本仓库前须先安装平台主工程：

```bash
cd j2agent && mvn clean install
```

`agents/pom.xml` 以 `j2agent-bom` 为 parent，第三方依赖无需写 `<version>`。`j2agent-server` 版本在 `agents/pom.xml` 的 `dependencyManagement` 中声明。

## 继承关系

| 坐标 | 是否必须继承 | 用途 |
|------|-------------|------|
| `j2agent-plugins-agents`（`agents/pom.xml`） | **否** | 本仓库示例聚合，便于一键 `mvn package` |
| `j2agent-bom` | **推荐** | 第三方依赖版本统一管理 |

外部新 Agent 在任意 Git 仓库中维护**独立 `pom.xml`**，在工程内放置 `src/main/assemblies/agent-package.xml`（可参考示例 Agent）并配置打包插件，**不要**继承 `agents/pom.xml`。

## 一键编译（本仓库）

```bash
cd j2agent && mvn clean install
cd ../j2agent-plugins-agents/agents && mvn clean package
```

## 单个 Agent 独立打包

```bash
cd agents/j2agent-qa-assistant
mvn clean package
```

| 产物 | 说明 |
|------|------|
| `target/<artifactId>-<version>/` | **解压目录**（瘦 JAR + `resources/`） |
| `target/<artifactId>-<version>.tar.gz` | 便于分发的压缩包，内容与上者一致 |

部署（二选一）：

```bash
PLUGIN_PATH=/opt/j2agent/volume/plugins/agents
DEST="$PLUGIN_PATH/j2agent-qa-assistant-1.0.0-SNAPSHOT"
mkdir -p "$DEST"

# 方式 A：直接使用 target 下解压目录
cp -a agents/j2agent-qa-assistant/target/j2agent-qa-assistant-1.0.0-SNAPSHOT/. "$DEST/"

# 方式 B：分发 tar.gz（解压到已建好的目标目录）
tar -xzf agents/j2agent-qa-assistant/target/j2agent-qa-assistant-1.0.0-SNAPSHOT.tar.gz -C "$DEST"

# POST /v1/rest/j2agent/agents/reload
```

详见 [Agent 开发文档](../j2agent-docs/agent开发/文档/Agent开发.md)。

## 新建 Agent 工程（外部仓库）

推荐直接复制 [`0_example-agent`](agents/0_example-agent/) 目录，按需改名后开发。亦可自行维护 `pom.xml` 与 `src/main/assemblies/agent-package.xml`。

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.github.jerryt92.j2agent</groupId>
    <artifactId>j2agent-bom</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>
  <groupId>com.example</groupId>
  <artifactId>my-custom-agent</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
    <j2agent.version>1.0.0-SNAPSHOT</j2agent.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.github.jerryt92.j2agent</groupId>
        <artifactId>j2agent-server</artifactId>
        <version>${j2agent.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>io.github.jerryt92.j2agent</groupId>
      <artifactId>j2agent-server</artifactId>
      <scope>provided</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-jar-plugin</artifactId>
        <configuration>
          <excludes><exclude>io/github/jerryt92/j2agent/**</exclude></excludes>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-assembly-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

若将新示例 Agent 放入本仓库 `agents/` 目录，可在 [`agents/pom.xml`](agents/pom.xml) 的 `<modules>` 中追加模块名以便一键编译。
