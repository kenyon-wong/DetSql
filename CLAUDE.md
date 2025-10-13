# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

# DetSql 项目开发规范

## 项目概述

DetSql 是一个基于 Burp Suite Montoya API 的 SQL 注入检测扩展，支持多种注入类型的自动化检测。

**核心技术栈**：
- Java 17
- Burp Suite Montoya API 2025.8
- Maven (maven-shade-plugin)
- Gson 2.11.0、Apache Commons Lang3 3.18.0、Commons Text 1.13.0

**项目结构**：
```
DetSql/
├── src/main/java/DetSql/
│   ├── DetSql.java                      # 主扩展类 (BurpExtension 入口)
│   ├── MyHttpHandler.java               # HTTP 处理核心逻辑 (SQL注入检测引擎)
│   ├── MyFilterRequest.java             # 请求过滤器 (白/黑名单、后缀过滤)
│   ├── MyCompare.java                   # 相似度计算 (Levenshtein、Jaccard)
│   ├── SourceTableModel.java            # 原始请求表模型
│   ├── PocTableModel.java               # PoC测试结果表模型
│   ├── SourceLogEntry.java              # 原始请求日志条目
│   ├── PocLogEntry.java                 # PoC测试日志条目
│   └── MyExtensionUnloadingHandler.java # 扩展卸载处理器
├── .agent/                              # 工作暂存区（设计文档、分析报告）
├── pom.xml                              # Maven 配置
└── CLAUDE.md                            # 本文件
```

## 核心架构设计

### 数据流架构

```
HTTP请求 → MyFilterRequest (过滤)
         ↓
    MyHttpHandler (检测引擎)
         ↓
    创建多线程测试任务
         ↓
    5种检测类型并发执行:
    1. 报错注入 (Error-based)
    2. 数字注入 (Numeric)
    3. 字符注入 (String-based)
    4. Order注入 (Order By)
    5. 布尔注入 (Boolean-based)
         ↓
    MyCompare (相似度计算)
         ↓
    结果存储到 attackMap
         ↓
    UI 表格更新 (SourceTableModel/PocTableModel)
```

### 核心设计模式

1. **信号量控制并发**
   - `semaphore`: 控制整体并发线程数（避免过载）
   - `semaphore2`: 控制单个请求的测试并发度
   - 位置: `MyHttpHandler.java`

2. **SM3 哈希去重**
   - 使用 SM3 算法对请求生成唯一标识，避免重复测试
   - `CryptoUtils.generateDigest(ByteArray, DigestAlgorithm.SM3)`
   - 去重逻辑在 `MyFilterRequest.getUnique()` 中实现

3. **线程中断机制**
   - 使用线程名称（设置为SM3哈希）实现精确中断
   - 右键菜单 "End this data" 通过遍历所有线程找到目标线程并中断
   - 位置: `DetSql.java` provideMenuItems()

4. **双表模型**
   - `SourceTableModel`: 显示原始请求
   - `PocTableModel`: 显示每个请求的所有PoC测试结果
   - 通过 `attackMap` (ConcurrentHashMap) 关联

5. **相似度判断策略**
   - **Levenshtein距离**: 用于报错/数字/字符/布尔注入
   - **Jaccard相似度**: 用于Order注入
   - **响应长度阈值**: 辅助判断（长度差>100直接判定不相似）
   - **前后缀去除**: 删除相同部分后再计算（减少干扰）

6. **实时进度统计**
   - **设计理念**: 简洁优先，不引入复杂的队列机制
   - **数据来源**: 复用现有的 `countId`（已测试数量）和 `attackMap`（漏洞数量）
   - **更新机制**: 使用 `javax.swing.Timer` 每秒自动刷新 UI
   - **实现成本**: <50行代码，35分钟完成
   - **Linus 评价**: "Perfect is the enemy of good. 用最简单的方法解决80%的问题。"
   - **参考**: `.agent/progress-stats-analysis.md`

### SQL注入检测逻辑

#### 1. 报错注入 (Error-based)
- **Payloads**: `'`, `"`, `` ` ``, `%DF'`, `%DF"` 等
- **判断**: 响应体匹配 ~100 条正则规则
- **特殊处理**: JSON参数使用Unicode编码的引号 (`\u0022`, `\u0027`)

#### 2. 数字注入 (Numeric)
- **条件**: 参数值为纯数字
- **测试序列**:
  - `value-0-0-0` → 与原始响应相似度 >90%
  - `value-abc` → 与原始响应和第一次响应相似度都 <90%

#### 3. 字符注入 (String-based)
- **测试序列**:
  - `value'` → 与原始响应不相似
  - `value''` → 与单引号响应不相似
  - `value'+'` 或 `value'||'` → 与原始响应相似
- **特殊处理**: JSON参数必须被双引号包裹才测试

#### 4. Order注入 (Order By)
- **测试序列**:
  - `value,0` → 与原始响应不相似
  - `value,xxxxxx` → 与原始响应不相似
  - `value,1` 或 `value,2` → 与原始响应相似

#### 5. 布尔注入 (Boolean-based)
- **测试序列**:
  - `value'||EXP(710)||'` → 触发错误
  - `value'||EXP(290)||'` → 正常
  - `value'||1/0||'` → 触发错误（备选）
  - `value'||1/1||'` → 与EXP(290)相似

## Git Commit Standards

### Commit Message Format

**REQUIRED**: All commit messages must follow [Conventional Commits](https://www.conventionalcommits.org/) specification and **MUST be written in Chinese**.

**Format**:
```
<类型>: <简短描述>

[可选的详细描述]

[可选的脚注]
```

**Types (类型)**:
- `feat`: 新功能
- `fix`: 修复 bug
- `refactor`: 重构（既不是新功能也不是修复）
- `perf`: 性能优化
- `style`: 代码格式调整（不影响功能）
- `test`: 测试相关
- `docs`: 文档更新
- `chore`: 构建/工具/依赖更新
- `ci`: CI/CD 配置更新

**Examples**:
```bash
# Good ✅
git commit -m "feat: 添加 Boolean 注入检测"
git commit -m "fix: 修复 HTTP 请求响应中文乱码问题"
git commit -m "refactor: 简化编码转换逻辑"

# Bad ❌
git commit -m "feat: implement boolean injection"  # 必须用中文
git commit -m "update code"  # 缺少类型前缀
git commit -m "🤖 Generated with Claude Code"  # 不要添加 AI 标识
```

### Commit Content Rules

**DO NOT include**:
- ❌ AI collaboration identifiers (e.g., "🤖 Generated with Claude Code")
- ❌ Co-authored-by AI signatures
- ❌ Tool attribution in commit messages

**DO include**:
- ✅ Clear, concise Chinese descriptions
- ✅ Reference to related issues (e.g., "fixes #123")
- ✅ Breaking changes notice (e.g., "BREAKING CHANGE: ...")

**Example of complete commit**:
```bash
git commit -m "$(cat <<'EOF'
fix: 修复 HTTP 请求响应中文乱码问题

## 问题描述
当 HTTP 请求/响应包含非 UTF-8 编码的中文字符时（如 GBK、GB2312），
代码错误地使用 `new String(bytes, UTF-8)` 强制按 UTF-8 解码，导致中文显示为乱码。

## 根本原因
代码绕过了 Burp Montoya API 的智能编码检测机制。

## 解决方案
使用 Montoya API 提供的 `ByteArray.toString()` 方法，
让 Burp Suite 使用正确检测到的编码进行转换。

## 影响
- 修复 JSON/XML 参数中的中文乱码
- 修复响应体中的中文乱码
- 提升 SQL 注入检测准确性

参考: .agent/fix-chinese-encoding.md
EOF
)"
```

## 常用开发命令

### 编译和打包
```bash
# 完整编译（清理 + 编译）
mvn clean compile

# 打包为可用的JAR（跳过测试）
mvn clean package -DskipTests

# 验证JAR内容
jar tf target/DetSql-2.7.jar | head -20
jar tf target/DetSql-2.7.jar | grep -E "DetSql|gson|commons"

# 检查JAR大小（应为 ~1.1MB）
ls -lh target/DetSql-*.jar
```

### 测试和调试
```bash
# 编译时显示详细警告
mvn compile -Xlint:deprecation

# 在Burp Suite中测试
# 1. Extensions → Installed → Add → 选择 target/DetSql-2.7.jar
# 2. 查看 Burp Suite Output 标签页的加载日志
# 3. 检查 DetSql 标签页是否正常显示

# 快速重新加载扩展（开发时）
# 1. Extensions → Installed → 右键卸载旧版本
# 2. 重新打包: mvn clean package -DskipTests
# 3. 加载新版本 JAR
```

### Git操作
```bash
# 查看当前状态
git status
git diff

# 提交（必须使用中文和 Conventional Commits 格式）
git commit -m "feat: 添加新功能"

# 推送到远程
git push origin master

# 创建版本标签
git tag -a v2.7 -m "Release v2.7"
git push origin master --tags
```

## 开发工作流

### 1. 问题分析阶段

使用深度思考和子代理分析问题：

```bash
# 创建分析文档
.agent/<feature-name>-analysis.md

# 内容结构
## 问题描述
## 根本原因（Linus 五层思考法）
## 技术方案
## 预期影响
```

### 2. 代码实现阶段

**原则**：
- ✅ 原子化修改：每次只修复一个问题
- ✅ 最小变更：避免重构无关代码
- ✅ 保持向后兼容：不破坏现有功能
- ✅ 使用 API 提供的方法：不要重新发明轮子

**关键代码位置**：
- **扩展入口**: `DetSql.java:initialize()` (第98行)
- **HTTP处理**: `MyHttpHandler.java:handleHttpResponseReceived()`
- **请求过滤**: `MyFilterRequest.java:filter()`
- **相似度计算**: `MyCompare.java:levenshtein()`, `jaccard()`
- **报错注入检测**: `MyHttpHandler.java` 搜索 `errorsqlInject`
- **数字注入检测**: `MyHttpHandler.java` 搜索 `numsqlInject`
- **字符注入检测**: `MyHttpHandler.java` 搜索 `stringsqlInject`
- **Order注入检测**: `MyHttpHandler.java` 搜索 `ordersqlInject`
- **布尔注入检测**: `MyHttpHandler.java` 搜索 `boolsqlInject`
- **配置加载/保存**: `DetSql.java:initialize()` 和 `getConfigComponent()`
- **进度统计**: `MyHttpHandler.java:countId` 和 `attackMap`（复用现有数据）
- **内存管理**: `MyHttpHandler.java:attackMap` 和测试完成回调

**代码风格**：
```java
// ✅ Good: 使用 API 提供的方法
String body = response.body().toString();

// ❌ Bad: 绕过 API 自己实现
String body = new String(response.body().getBytes(), StandardCharsets.UTF_8);

// ✅ Good: 简洁明了
if (params.isEmpty()) return;

// ❌ Bad: 不必要的复杂性
if (!params.isEmpty()) {
    // ... do something
}
```

### 3. 测试验证阶段

**必须执行的测试**：
```bash
# 1. 编译测试
mvn clean compile

# 2. 打包测试
mvn clean package -DskipTests

# 3. 验证 JAR 文件
ls -lh target/DetSql-*.jar
jar tf target/DetSql-*.jar | grep -E "DetSql|gson|commons"

# 4. 功能测试（在 Burp Suite 中）
- 加载扩展
- 测试核心功能
- 验证修复效果
```

### 4. 提交推送阶段

```bash
# 1. 查看修改
git status
git diff

# 2. 暂存文件
git add <files>

# 3. 提交（遵循 Commit Standards）
git commit -m "..."

# 4. 推送
git push origin master
```

## Maven 配置规范

### 依赖管理

**Scope 规则**：
```xml
<!-- Burp Suite 已提供，标记为 provided -->
<dependency>
    <groupId>net.portswigger.burp.extensions</groupId>
    <artifactId>montoya-api</artifactId>
    <version>2025.8</version>
    <scope>provided</scope>
</dependency>

<!-- 需要打包到 JAR 的依赖 -->
<dependency>
    <groupId>com.google.code.gson</groupId>
    <artifactId>gson</artifactId>
    <version>2.11.0</version>
</dependency>
```

### 打包配置

**必须使用 maven-shade-plugin**：
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>3.5.1</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals>
                <goal>shade</goal>
            </goals>
            <configuration>
                <filters>
                    <filter>
                        <artifact>*:*</artifact>
                        <excludes>
                            <exclude>META-INF/*.SF</exclude>
                            <exclude>META-INF/*.DSA</exclude>
                            <exclude>META-INF/*.RSA</exclude>
                        </excludes>
                    </filter>
                </filters>
            </configuration>
        </execution>
    </executions>
</plugin>
```

**原因**：Burp Suite 需要单一的 "fat jar"，包含所有依赖。

## Burp Suite Montoya API 使用规范

### 字符编码处理

**✅ 正确方式**：
```java
// 使用 API 提供的编码感知方法
String requestBody = request.toByteArray().toString();
String responseBody = response.body().toString();
```

**❌ 错误方式**：
```java
// 绕过 API，强制 UTF-8 编码
String body = new String(
    response.body().getBytes(),
    StandardCharsets.UTF_8
);
```

**原因**：
- Burp Suite 会自动检测 `Content-Type: charset=xxx`
- `ByteArray.toString()` 使用 Burp 检测到的正确编码
- 强制 UTF-8 会导致 GBK/GB2312 等编码的中文乱码

### HTTP 请求/响应处理

**推荐模式**：
```java
// 获取请求/响应内容
String requestStr = httpRequest.toByteArray().toString();
String responseStr = httpResponse.body().toString();

// 参数处理
List<ParsedHttpParameter> params = httpRequest.parameters();
for (ParsedHttpParameter param : params) {
    String name = param.name();
    String value = param.value();
    // ... 处理参数
}

// 修改请求
HttpRequest newRequest = httpRequest.withBody(newBody);
```

### 扩展生命周期

```java
@Override
public void initialize(MontoyaApi api) {
    // 1. 设置扩展名称
    api.extension().setName("DetSql");

    // 2. 注册处理器
    api.http().registerHttpHandler(myHttpHandler);

    // 3. 注册卸载处理器
    api.extension().registerUnloadingHandler(unloadingHandler);

    // 4. 注册 UI 组件
    api.userInterface().registerSuiteTab("DetSql", component);

    // 5. 日志输出
    api.logging().logToOutput("DetSql loaded successfully");
}
```

## 重要技术注意事项

### 并发控制
- **信号量配置**:
  - 主信号量: 控制整体最大并发线程数
  - 子信号量: 控制单个请求的测试并发度
  - 避免修改信号量数量,除非明确理解其影响

### 请求去重机制
- **SM3哈希**: 基于 `url+method+params+headers` 生成唯一标识
- **特殊处理**: Repeater 模块的请求不去重（可重复测试）
- **位置**: `MyFilterRequest.getUnique()`

### 字符编码处理（重要！）
- **问题根源**: HTTP响应可能使用 GBK、GB2312 等非UTF-8编码
- **错误做法**: `new String(bytes, StandardCharsets.UTF_8)` 会导致中文乱码
- **正确做法**: 使用 Montoya API 的 `ByteArray.toString()` 和 `body().toString()`
- **原理**: Burp Suite 会自动检测 Content-Type charset 并使用正确编码

### 响应体长度限制
- **限制1**: 响应体 >50000 字节的请求不测试（避免性能问题）
- **限制2**: 响应体 10000-50000 字节的请求使用较低线程数
- **位置**: `MyHttpHandler.java` 中的长度检查逻辑

### UI线程安全
- **TableModel更新**: 必须通过 `fireTableDataChanged()` 等方法
- **跨线程访问**: UI组件访问需要注意线程安全
- **位置**: `SourceTableModel.java`, `PocTableModel.java`

### 配置持久化
- **配置文件**: `~/DetSqlConfig.txt` (用户家目录)
- **加载时机**: 扩展初始化时自动加载
- **保存时机**: 用户点击 "保存" 按钮
- **格式**: Java Properties 格式

### 内存管理和数据保留策略（重要！）

**当前架构存在的问题**：
- **内存泄漏**: `attackMap` 永不清理，100个接口测试后可能占用 ~57.5MB
- **数据不同步**: "delete novuln history" 只删除 UI，`attackMap` 中数据仍然存在
- **空指针风险**: 清理后点击 UI 行可能触发空指针异常

**内存占用计算**：
```
100接口 × 5参数/接口 × 23测试/参数 × 5KB/测试 = 57.5MB
即使删除所有 UI 行，attackMap 仍占用这些内存！
```

**推荐的演进路径**：

**阶段1：短期修复（1-2天）**
```java
// 1. 测试完成后清理无漏洞的 HTTP 数据（约15行）
if (!hasVulnerability) {
    pocEntry.clearHttpData();  // 保留元信息，清理完整请求/响应
}

// 2. "delete novuln history" 同步删除 attackMap（约5行）
attackMap.remove(sourceEntry.getHash());

// 3. UI 检查空指针（约10行）
if (httpRequestResponse != null) {
    // ... 显示数据
} else {
    // ... 显示友好提示
}
```

**预期效果**：
- 内存占用从 57.5MB 降到 13.5MB（保留元信息）
- 修复数据不同步问题
- 避免空指针异常

**阶段2：长期重构（1-2周，可选）**

**Linus 评价**：
```
"Bad programmers worry about the code.
 Good programmers worry about data structures."

当前问题的根源是数据结构设计缺陷：
- attackMap 混合了"测试上下文"和"结果存储"两个职责
- 需要到处检查空指针（特殊情况）

正确的做法：三层数据模型
```

**三层数据模型（推荐的长期方案）**：
```java
// 1. 测试上下文（测完即丢，GC自动回收）
class TestContext {
    HttpRequestResponse originalRequest;
    List<HttpRequestResponse> testRequests;
    // 测试完成后，这个对象被丢弃，内存自动释放
}

// 2. 扫描结果（仅元信息，UI展示）
class ScanResult {
    String url;
    String method;
    List<String> parameters;
    int testCount;
    boolean hasVulnerability;
    // 不保存完整 HTTP 包
}

// 3. 漏洞证据（仅保存有漏洞的）
class VulnerabilityEvidence {
    String vulnerabilityType;
    String payload;
    HttpRequestResponse proofRequest;  // 仅漏洞请求
    HttpRequestResponse proofResponse; // 仅漏洞响应
}
```

**三层模型的优势**：
- ✅ 消除所有特殊情况（无需空指针检查）
- ✅ GC 自动回收测试数据（零手动管理）
- ✅ 数据一致性天然保证（UI和内存完全同步）
- ✅ 内存占用降到 <1MB（仅保存必要数据）
- ✅ 符合"好品味"原则（职责分离清晰）

**实施建议**：
1. **立即实施阶段1**：快速修复内存泄漏和数据不同步
2. **评估后决定阶段2**：如果内存问题频繁，考虑长期重构

**参考文档**：
- `.agent/compromise-strategy-analysis.md` - 短期修复方案详细分析
- `.agent/data-retention-analysis.md` - 数据保留策略对比
- `.agent/detsql-deep-analysis-report.md` - 架构深度分析

## 常见问题处理

### 1. 扩展加载失败

**错误**：`Extension class is not a recognized type`

**原因**：JAR 文件不包含依赖

**解决**：
```bash
# 1. 确认 pom.xml 包含 maven-shade-plugin
# 2. Montoya API 标记为 provided
# 3. 重新打包
mvn clean package -DskipTests

# 4. 验证 JAR 大小
ls -lh target/DetSql-*.jar
# 应该是 ~1.1MB，不是 ~92KB
```

### 2. 中文乱码

**现象**：中文参数或响应显示为乱码

**原因**：错误使用 `new String(bytes, UTF-8)`

**解决**：使用 `ByteArray.toString()` 或 `body().toString()`

### 3. 编译警告

**警告**：`uses or overrides a deprecated API`

**处理**：
```bash
# 查看详细信息
mvn compile -Xlint:deprecation

# 评估是否需要修复
# 如果是第三方库的问题，可以暂时忽略
```

## 工作暂存区（.agent/）

使用 `.agent/` 目录存储：
- 设计文档
- 分析报告
- TODO 列表
- 技术调研

**命名规范**：
```
.agent/
├── fix-chinese-encoding.md            # 问题修复方案
├── feature-boolean-injection.md       # 新功能设计
├── analysis-performance-issue.md      # 性能问题分析
├── progress-stats-analysis.md         # 进度统计功能可行性分析
├── compromise-strategy-analysis.md    # 折中数据保留策略分析
├── data-retention-analysis.md         # 数据保留策略对比分析
└── detsql-deep-analysis-report.md     # DetSql 深度架构分析报告
```

**文档类型**：
- `fix-*` - 问题修复方案
- `feature-*` - 新功能设计
- `analysis-*` - 技术分析报告
- `*-report` - 深度分析报告

## 性能优化建议

### 1. 减少不必要的测试
- **配置白名单**: 在 Config 标签页设置目标域名（如 `example.com|test.com`）
- **黑名单过滤**: 排除已知的静态资源域名
- **后缀过滤**: 默认已排除常见静态文件后缀（js/css/jpg等）

### 2. 调整并发和延迟
- **延迟时间**: Config → 延迟时间（ms），用于随机延迟请求
- **固定间隔**: Config → 请求间固定间隔（ms），默认100ms
- **间隔范围**: Config → 请求间间隔范围（ms），用于随机间隔

### 3. 选择性检测
- **只测报错**: 勾选 "测试报错类型"，取消其他类型（最快）
- **关闭Cookie测试**: 如果不需要测试Cookie参数
- **使用自定义Payload**: 仅测试特定的注入模式

### 4. 响应体优化
- 大响应体（>50000字节）自动跳过
- 中等响应体（10000-50000字节）使用较低并发

## 调试技巧

### 查看日志
```java
// 在代码中添加日志（开发时）
api.logging().logToOutput("Debug message");
api.logging().logToError("Error message");
```

### Burp Suite 输出
- **查看位置**: Burp Suite → Output 标签页
- **加载日志**: 扩展加载时会输出版本和作者信息
- **调试日志**: 可以在代码中使用 `api.logging().logToOutput()` 输出调试信息

### 手动停止测试
- **右键菜单**: 在 DashBoard 表格中右键点击请求
- **选择 "End this data"**: 停止该请求的所有测试
- **原理**: 通过线程名称（SM3哈希）找到对应线程并中断

### 验证相似度计算
```java
// 测试 Levenshtein 距离
int distance = MyCompare.levenshtein(str1, str2);
double similarity = 1.0 - (double) distance / Math.max(str1.length(), str2.length());

// 测试 Jaccard 相似度
double similarity = MyCompare.jaccard(str1, str2);
```

## 文档更新

修改代码后，同步更新：
- ✅ README.md（如果影响用户使用）
- ✅ .agent/ 目录中的相关文档
- ✅ 代码注释（如果是复杂逻辑）

❌ **不要创建**：
- 冗长的设计文档（除非必要）
- 过度详细的 API 文档
- 重复的 README

## 版本发布

发布新版本时：
```bash
# 1. 更新版本号（pom.xml）
<version>2.7</version>

# 2. 打包
mvn clean package -DskipTests

# 3. 创建 tag
git tag -a v2.7 -m "Release v2.7"

# 4. 推送
git push origin master --tags

# 5. 创建 GitHub Release
# - 上传 target/DetSql-2.7.jar
# - 编写 Release Notes（中文）
```

## 参考资源

- [Burp Montoya API 文档](https://portswigger.github.io/burp-extensions-montoya-api/)
- [Conventional Commits](https://www.conventionalcommits.org/)
- [Maven Shade Plugin](https://maven.apache.org/plugins/maven-shade-plugin/)
