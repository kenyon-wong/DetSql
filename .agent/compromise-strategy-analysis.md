# DetSql 折中数据保留策略可行性分析

## 策略描述

**折中策略 (激进策略的改进版)**:

1. **未检测到漏洞的接口**:
   - ✅ 保留原始数据包和元信息 (URL、方法、参数等)
   - ❌ 删除所有 payload 测试数据包

2. **检测到漏洞的接口**:
   - ✅ 保留所有数据 (原始包 + 所有测试包)

3. **"delete novuln history" 菜单项**:
   - 功能: 处理结果表格 (不是内存中的 attackMap)
   - 作用: 清理未检测到漏洞的历史记录

---

## 当前数据结构分析

### SourceLogEntry (原始请求日志)

```java
public class SourceLogEntry {
    private int id;                              // 序号
    private String tool;                          // 来源 (Proxy/Repeater/Send)
    private String myHash;                        // SM3哈希或时间戳 (关联键)
    private String vulnState;                     // 漏洞状态: "run" / "" / "-errsql-stringsql" / "手动停止"
    private int bodyLength;                       // 响应体长度
    private HttpRequestResponse httpRequestResponse;  // 完整HTTP包 (原始请求+响应)
    private String httpService;                   // 服务地址
    private String method;                        // HTTP方法
    private String path;                          // 路径
}
```

**关键字段**:
- `myHash`: 唯一标识符，用于关联 attackMap
- `vulnState`: 检测结果标识
  - `"run"`: 正在测试
  - `""`: 测试完成但未发现漏洞
  - `"-errsql-stringsql-numsql"`: 发现的漏洞类型
  - `"手动停止"`: 用户手动停止
- `httpRequestResponse`: **仅在发现漏洞时保留原始包**，否则为 `null`

### PocLogEntry (PoC测试结果)

```java
public class PocLogEntry {
    private String name;                          // 参数名
    private String poc;                           // Payload
    private String similarity;                    // 相似度百分比
    private String vulnState;                     // 漏洞类型 (errsql/stringsql/numsql等)
    private String bodyLength;                    // 响应体长度
    private String statusCode;                    // 状态码
    private String time;                          // 响应时间(秒)
    private HttpRequestResponse httpRequestResponse;  // 完整HTTP包 (测试请求+响应)
    private String myHash;                        // 关联的原始请求哈希
}
```

**每个测试都创建一个 PocLogEntry**:
- 报错注入: ~10个 payload × N个参数 = ~10N 个 PocLogEntry
- 数字注入: 2个 payload × N个参数 = 2N 个
- 字符注入: 3-4个 payload × N个参数 = ~4N 个
- Order注入: 4个 payload × N个参数 = 4N 个
- Boolean注入: 3个 payload × N个参数 = 3N 个
- **总计**: ~23N 个 PocLogEntry (N = 参数数量)

### attackMap 关系

```
attackMap: ConcurrentHashMap<String, List<PocLogEntry>>

┌─────────────────────────────────────────┐
│ Key: SM3Hash (myHash)                   │
│ Value: List<PocLogEntry>                │
├─────────────────────────────────────────┤
│ "A1B2C3..." → [PocLogEntry1,            │
│                PocLogEntry2,            │
│                ...                       │
│                PocLogEntry23]           │
│                                          │
│ "D4E5F6..." → [PocLogEntry24, ...]     │
└─────────────────────────────────────────┘
```

**数据关联流程**:
```
用户点击 DashBoard 中的某一行
  ↓
SourceLogEntry entry = sourceTableModel.log.get(rowIndex)
  ↓
String hash = entry.getMyHash()
  ↓
List<PocLogEntry> pocs = myHttpHandler.attackMap.get(hash)
  ↓
pocTableModel.add(pocs)  // 更新Result表格
```

---

## Linus 五层思考分析

### 第一层: 数据结构分析

**核心数据关系**:

```
                ┌─────────────────────┐
                │  SourceTableModel   │
                │  (DashBoard表格)    │
                └──────────┬──────────┘
                           │
                           │ myHash
                           │
          ┌────────────────┼────────────────┐
          │                │                 │
          ▼                ▼                 ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│ attackMap       │  │ SourceLogEntry  │  │ PocTableModel   │
│ (所有测试数据)  │◄─┤ httpRequestResp │  │ (Result表格)    │
└─────────────────┘  └─────────────────┘  └─────────────────┘
```

**数据拥有权**:
1. **SourceLogEntry**:
   - 创建者: `MyHttpHandler.createLogEntry()`
   - 修改者: `MyHttpHandler.updateLogEntry()` (测试完成后更新 vulnState)
   - 删除者: **用户通过右键菜单** ("delete selected rows" / "delete novuln history")

2. **PocLogEntry**:
   - 创建者: 各个注入检测方法 (`testErrorInjection()`, `testStringInjection()`, 等)
   - 修改者: **从不修改** (immutable after creation)
   - 删除者: **从不删除** (attackMap 永不清理)

3. **attackMap**:
   - 创建时机: `MyHttpHandler.createLogEntry()` - `attackMap.put(hash, new ArrayList<>())`
   - 添加数据: 各个注入检测方法 - `attackList.add(pocEntry)`
   - **删除时机**: **永不删除** (代码中没有 `attackMap.remove()` 调用)

**致命问题**:
> **attackMap 是内存泄漏源头！**
> - UI 表格删除了条目，但 attackMap 永不删除
> - 长时间运行后，attackMap 积累数万个条目
> - 每个条目包含完整的 HttpRequestResponse (可能数KB到数MB)

### 第二层: 特殊情况识别

**场景1: 测试过程中删除**

```java
// MyHttpHandler.java:395
List<PocLogEntry> getAttackList = attackMap.get(requestSm3Hash);
// ... 测试过程中不断添加
getAttackList.add(pocEntry);
```

**问题**: 如果用户在测试期间删除了 UI 中的行:
1. SourceLogEntry 从 `sourceTableModel.log` 中移除
2. **attackMap 中的数据仍然存在**
3. **测试线程仍在运行，继续向 attackMap 添加数据**
4. 测试完成后尝试更新 UI → **找不到对应的行** → 报错或者无效操作

**场景2: "delete novuln history" 的实际行为**

```java
// DetSql.java:579-590
menuItem2.addActionListener(e -> {
    for (int i = sourceTableModel.log.size() - 1; i >= 0; i--) {
        if (vulnState.equals("") || vulnState.equals("手动停止")) {
            sourceTableModel.log.remove(entry);
            tableModel.fireTableRowsDeleted(i, i);
        }
    }
});
```

**实际效果**:
- ✅ 删除了 DashBoard 表格中的行
- ❌ **attackMap 中的数据未删除**
- ❌ **PocLogEntry 列表占用的内存未释放**

**内存泄漏计算**:
```
假设检测100个接口:
- 每个接口 5个参数
- 每个参数 23个测试 payload
- 每个 PocLogEntry 占用 ~5KB (包含 HttpRequestResponse)

总内存 = 100 × 5 × 23 × 5KB = 57.5MB
```

即使删除了 UI 中的所有行，**attackMap 仍然占用 57.5MB**！

**场景3: 如何区分"有漏洞"和"无漏洞"?**

当前代码通过 `SourceLogEntry.vulnState` 区分:
- `vulnState = ""` → 无漏洞
- `vulnState = "-errsql-stringsql"` → 有漏洞

**问题**: vulnState 只在测试完成后更新:

```java
// MyHttpHandler.java:283-306
private void updateLogEntry(..., String vulnType) {
    SwingUtilities.invokeLater(() -> {
        SourceLogEntry newEntry = new SourceLogEntry(
            finalLogIndex,
            ...,
            finalVulnType,  // "" or "-errsql-stringsql"
            ...,
            vulnType.isBlank() ? null : httpRequestResponse,  // ← 关键!
            ...
        );
        sourceTableModel.updateVulnState(newEntry, modelIndex, viewIndex);
    });
}
```

**观察**: `httpRequestResponse` 字段:
- 有漏洞: 保留原始 HttpRequestResponse
- 无漏洞: 设置为 `null`

**但是**: PocLogEntry 中的 `httpRequestResponse` **永远保留**!

### 第三层: 复杂度审查

**折中策略需要实现什么?**

1. **标记"无漏洞"状态**:
   - 当前已实现: `vulnState = ""`
   - 无需修改

2. **删除 payload 测试包**:
   - 需要遍历 `attackMap.get(hash)` 列表
   - 删除每个 `PocLogEntry` 的 `httpRequestResponse` 字段
   - **问题**: PocLogEntry 字段是 private，无 setter

3. **"delete novuln history" 实现**:
   - 当前仅删除 `sourceTableModel.log`
   - 需要同步删除 `attackMap` 中的条目

**代码修改量估算**:

```java
// 修改1: PocLogEntry 添加清理方法 (~10行)
public void clearHttpData() {
    this.httpRequestResponse = null;
}

// 修改2: 测试完成后清理无漏洞的数据 (~20行)
private void updateLogEntry(...) {
    SwingUtilities.invokeLater(() -> {
        // ... 现有代码 ...

        if (finalVulnType.isBlank()) {
            // 无漏洞 → 清理 attackMap 中的 HTTP 数据
            List<PocLogEntry> entries = attackMap.get(hash);
            for (PocLogEntry entry : entries) {
                entry.clearHttpData();
            }
        }
    });
}

// 修改3: "delete novuln history" 同步删除 attackMap (~5行)
menuItem2.addActionListener(e -> {
    for (int i = sourceTableModel.log.size() - 1; i >= 0; i--) {
        if (vulnState.equals("") || vulnState.equals("手动停止")) {
            String hash = entry.getMyHash();
            attackMap.remove(hash);  // ← 新增
            sourceTableModel.log.remove(entry);
            tableModel.fireTableRowsDeleted(i, i);
        }
    }
});
```

**总代码修改**: ~35行

### 第四层: 破坏性分析

**风险1: 测试期间删除数据会有问题吗?**

**场景**:
```
T0: 用户触发测试 → 创建 SourceLogEntry (vulnState="run")
T1: 测试进行中 → 不断向 attackMap 添加 PocLogEntry
T2: 用户点击 "delete selected rows"
T3: 测试完成 → updateLogEntry() 尝试更新 UI
```

**问题**:
```java
// MyHttpHandler.java:302-305
int rowIndex = sourceTableModel.log.indexOf(
    new SourceLogEntry(finalLogIndex, null, null, null, 0, null, null, null, null)
);
sourceTableModel.updateVulnState(newEntry, rowIndex, rowIndex);
```

如果 T2 时刻删除了条目，T3 时刻 `indexOf()` 返回 `-1` → `updateVulnState(-1, -1)` → **数组越界或无效操作**

**解决方案**: 检查 `rowIndex >= 0` 再更新

**风险2: 用户能否恢复误删的数据?**

按照折中策略:
- 无漏洞的接口 → PocLogEntry 的 httpRequestResponse 被清空 → **无法恢复**
- 用户误删后 → **无法重新查看测试结果**

**风险3: UI 更新会有问题吗?**

```java
// DetSql.java:514
List<PocLogEntry> pocLogEntries = myHttpHandler.attackMap.get(sm3Hash);
pocTableModel.add(pocLogEntries);
```

如果按照折中策略清空了 `PocLogEntry.httpRequestResponse`:
- PocTableModel 仍然显示表格 (参数名、payload、相似度等)
- 但点击某一行查看详情时 → `httpRequestResponse = null` → **空白页面或崩溃**

**需要修改**: 点击 PocLogEntry 时检查 `httpRequestResponse != null`

**风险4: 内存占用评估**

**当前 (激进策略)**:
```
100个接口 × 5参数 × 23测试 × 0KB = 0MB (全删除)
```

**折中策略**:
```
无漏洞接口 (80%):
  - SourceLogEntry: 100 × 80% × 1KB = 80KB
  - PocLogEntry (仅元数据): 100 × 80% × 5 × 23 × 0.2KB = 1.84MB

有漏洞接口 (20%):
  - SourceLogEntry: 100 × 20% × 1KB = 20KB
  - PocLogEntry (完整数据): 100 × 20% × 5 × 23 × 5KB = 11.5MB

总计: ~13.4MB
```

**对比激进策略**: 内存占用增加 **13.4MB**

### 第五层: 实用性验证

**这个折中方案解决了什么问题?**

1. **用户诉求**: "我想保留原始请求，但测试数据太占内存"
2. **折中方案**: 保留原始请求 + 元信息，删除测试包的 HTTP 数据

**但是**:

**问题1**: 用户真的需要保留原始请求吗?

分析现有功能:
- 用户可以从 Burp Suite 的 Proxy/Repeater 标签页中找到原始请求
- DetSql 的 DashBoard 只是**快捷入口**
- 如果需要重新测试，可以右键 "Send to DetSql"

**问题2**: "delete novuln history" 的真实使用场景是什么?

假设测试了1000个接口:
- 990个无漏洞 (噪音)
- 10个有漏洞 (重要)

用户想做什么?
- **清理噪音，专注于漏洞接口**
- 删除所有无漏洞历史后，DashBoard 只显示10个有漏洞的接口

**折中策略的价值**:
- ❌ 无法重新查看测试结果 (httpRequestResponse = null)
- ✅ 仍然可以看到"这个接口被测试过" (SourceLogEntry 保留)
- ✅ 仍然可以看到元信息 (URL、方法、参数名、payload、相似度)

**实用性评分**: 6/10

**理由**:
- ✅ 保留了元信息，用户可以了解测试情况
- ❌ 无法重新查看 HTTP 包，调试困难
- ❌ 增加了实现复杂度 (~35行代码 + 测试)
- ❌ 引入了新的边界情况 (空指针检查)

---

## 核心判断

### ✅ 值得做: **但需要修正方案**

**原因**:

1. **折中策略本身有价值**:
   - 保留测试历史记录和元信息
   - 节省内存 (相比完全保留)
   - 允许用户清理噪音

2. **但当前设计有致命缺陷**:
   - attackMap 永不清理 → 内存泄漏
   - "delete novuln history" 只删除 UI → 数据不同步
   - PocLogEntry.httpRequestResponse 清空后 → 用户体验差

3. **需要从根本上重新设计**:
   - 不应该修修补补
   - 应该重新思考数据生命周期

---

## 关键洞察

### 数据结构: 三个生命周期

当前代码混淆了三种不同的数据生命周期:

```
1. 测试期间 (Transient):
   - 需要: 完整的 HttpRequestResponse
   - 目的: 进行相似度计算、正则匹配
   - 生命周期: 测试完成后即可丢弃

2. 结果展示 (Persistent):
   - 需要: 元信息 (URL、参数名、payload、相似度、漏洞类型)
   - 目的: 在 UI 表格中展示
   - 生命周期: 用户删除前一直保留

3. 证据保存 (Archival):
   - 需要: 有漏洞的完整 HTTP 包
   - 目的: 生成报告、复现漏洞
   - 生命周期: 永久保存
```

**当前设计问题**: 所有数据都是 Archival (永久保存)

### 复杂度: 特殊情况无处不在

```
if (vulnType.isBlank()) {
    // 清空数据
} else {
    // 保留数据
}

if (httpRequestResponse != null) {
    // 显示详情
} else {
    // 显示错误
}

if (attackMap.containsKey(hash)) {
    if (entries.isEmpty()) {
        // ???
    } else {
        if (entries.get(0).getHttpRequestResponse() == null) {
            // ???
        }
    }
}
```

**Linus 定律**: "如果你需要超过3层缩进，你就已经完蛋了"

### 风险点: 数据不一致

```
sourceTableModel.log  ←→  attackMap
     (UI层)              (数据层)

当前: 不同步!
- UI删除 → attackMap 不删除
- UI更新 → attackMap 不更新
```

**数据库设计准则**: "一个事实只存一次"

当前设计违反了这个准则:
- `SourceLogEntry.httpRequestResponse` (原始包)
- `PocLogEntry.httpRequestResponse` (测试包)
- attackMap 持有 PocLogEntry 列表

**三处存储，三处不一致的可能**

---

## Linus 式方案

### 核心哲学

> "Talk is cheap. Show me the data structures."
> — Linus Torvalds

**问题根源**: 数据结构设计错误

**解决方案**: 重新设计数据结构，让特殊情况消失

### 方案 1: 三层数据模型 (推荐)

```java
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// 第一层: 测试期间 (Transient) - 测试完成后丢弃
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
class TestContext {
    String requestHash;
    HttpRequest sourceRequest;
    HttpResponse sourceResponse;
    List<TestResult> results;  // 临时结果
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// 第二层: 结果展示 (Persistent) - 用户删除前保留
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
class ScanResult {
    int id;
    String tool;
    String url;
    String method;
    String path;
    int bodyLength;
    String vulnTypes;  // "" or "-errsql-stringsql"
    List<TestMetadata> testDetails;  // 仅元信息
}

class TestMetadata {
    String paramName;
    String payload;
    String similarity;
    String vulnType;
    int statusCode;
    int bodyLength;
    double responseTime;
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// 第三层: 证据保存 (Archival) - 仅保存有漏洞的
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
class VulnerabilityEvidence {
    String requestHash;
    HttpRequestResponse originalRequest;     // 原始包
    List<HttpRequestResponse> exploitRequests;  // 触发漏洞的测试包
}
```

**数据流**:

```
1. 测试开始
   → 创建 TestContext
   → 创建 ScanResult (vulnTypes = "run")
   → 添加到 sourceTableModel

2. 测试进行中
   → 在 TestContext 中存储完整 HTTP 包
   → 计算相似度、匹配正则

3. 测试完成
   → 提取元信息 → ScanResult.testDetails
   → 如果有漏洞:
       - 创建 VulnerabilityEvidence
       - 保存完整 HTTP 包
   → 丢弃 TestContext (GC 自动回收)

4. 用户删除
   → 从 sourceTableModel 删除 ScanResult
   → 如果有对应的 VulnerabilityEvidence:
       - 询问用户: "删除证据吗?"
       - 用户确认后删除
```

**优势**:

1. **消除特殊情况**:
   - 无需判断 `httpRequestResponse == null`
   - 无需判断 `vulnType.isBlank()`
   - 无需手动清理内存

2. **数据一致性**:
   - 每层数据独立管理
   - 删除操作原子化
   - 无法出现"UI删了但内存没删"的情况

3. **内存占用**:
   ```
   无漏洞 (80%):
     ScanResult + TestMetadata = ~2MB
     VulnerabilityEvidence = 0MB

   有漏洞 (20%):
     ScanResult + TestMetadata = ~0.5MB
     VulnerabilityEvidence = ~12MB

   总计: ~14.5MB (与折中策略相当)
   ```

4. **代码复杂度**:
   - 新增3个类 (~100行)
   - 修改测试流程 (~50行)
   - **删除**大量特殊情况判断 (减少~100行)
   - **净增加**: ~50行

### 方案 2: 最小修改方案 (快速修复)

如果不想重构数据结构，可以采用最小修改:

```java
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// 修改1: 测试完成后清理无漏洞的 HTTP 数据
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
private void updateLogEntry(..., String vulnType) {
    SwingUtilities.invokeLater(() -> {
        // ... 现有代码 ...

        if (vulnType.isBlank()) {
            // 无漏洞 → 清理 HTTP 数据 (释放内存)
            List<PocLogEntry> entries = attackMap.get(hash);
            if (entries != null) {
                for (PocLogEntry entry : entries) {
                    // 清空 HTTP 包，保留元信息
                    entry.setHttpRequestResponse(null);
                }
            }
        }
    });
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// 修改2: PocLogEntry 添加 setter (违反不可变性，但简单)
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
public void setHttpRequestResponse(HttpRequestResponse httpRequestResponse) {
    this.httpRequestResponse = httpRequestResponse;
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// 修改3: "delete novuln history" 同步删除 attackMap
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
menuItem2.addActionListener(e -> {
    for (int i = sourceTableModel.log.size() - 1; i >= 0; i--) {
        SourceLogEntry entry = sourceTableModel.log.get(i);
        String vulnState = entry.getVulnState();

        if (vulnState.equals("") || vulnState.equals("手动停止")) {
            String hash = entry.getMyHash();

            // 同步删除 attackMap
            myHttpHandler.attackMap.remove(hash);

            // 删除 UI
            sourceTableModel.log.remove(entry);
            sourceTableModel.fireTableRowsDeleted(i, i);
        }
    }
});

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// 修改4: 点击 PocLogEntry 时检查 null
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Override
public void changeSelection(int rowIndex, ...) {
    PocLogEntry logEntry = pocTableModel.get(rowIndex);

    if (logEntry.getHttpRequestResponse() != null) {
        requestViewer.setRequest(logEntry.getHttpRequestResponse().request());
        responseViewer.setResponse(logEntry.getHttpRequestResponse().response());
    } else {
        // 显示提示: "HTTP数据已清理,仅保留元信息"
        requestViewer.setRequest(HttpRequest.httpRequest());
        responseViewer.setResponse(HttpResponse.httpResponse());
    }

    super.changeSelection(rowIndex, columnIndex, toggle, extend);
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// 修改5: 测试完成时检查行是否仍存在
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
private void updateLogEntry(...) {
    SwingUtilities.invokeLater(() -> {
        int rowIndex = sourceTableModel.log.indexOf(
            new SourceLogEntry(finalLogIndex, null, null, null, 0, null, null, null, null)
        );

        // 检查行是否仍存在 (防止测试期间被删除)
        if (rowIndex >= 0) {
            sourceTableModel.updateVulnState(newEntry, rowIndex, rowIndex);
        }
        // 否则静默失败 (用户已手动删除,不需要更新)
    });
}
```

**代码修改量**: ~30行

**优势**:
- ✅ 修改最小
- ✅ 修复了 attackMap 泄漏
- ✅ 修复了数据不一致

**劣势**:
- ❌ 引入了空指针检查
- ❌ 违反了不可变性原则
- ❌ 没有从根本上解决架构问题

---

## 实施步骤

### 如果选择方案1 (三层数据模型)

1. **创建新的数据类** (~100行)
   ```java
   TestContext.java
   ScanResult.java
   TestMetadata.java
   VulnerabilityEvidence.java
   ```

2. **重构 MyHttpHandler** (~100行)
   - 测试流程使用 TestContext
   - 测试完成后提取元信息
   - 仅保存有漏洞的证据

3. **更新 UI 表格模型** (~50行)
   - SourceTableModel 使用 ScanResult
   - PocTableModel 使用 TestMetadata
   - 删除操作同步到所有层

4. **测试验证**
   - 测试无漏洞接口 → 内存不增长
   - 测试有漏洞接口 → 证据完整保存
   - 删除操作 → 内存立即释放

### 如果选择方案2 (最小修改)

1. **修改 PocLogEntry** (~5行)
   - 添加 `setHttpRequestResponse()` setter

2. **修改 updateLogEntry()** (~15行)
   - 无漏洞时清空 HTTP 数据

3. **修改 "delete novuln history"** (~5行)
   - 同步删除 attackMap

4. **修改 PocTableModel changeSelection()** (~10行)
   - 检查 `httpRequestResponse != null`

5. **修改 updateLogEntry()** (~5行)
   - 检查 `rowIndex >= 0`

6. **测试验证**
   - 测试无漏洞接口 → 内存减少
   - 删除无漏洞历史 → attackMap 同步删除
   - 点击已清理的 PocLogEntry → 显示提示

---

## 与激进策略对比

| 维度                  | 激进策略            | 折中策略 (方案2)    | 三层模型 (方案1)    |
|---------------------|-----------------|----------------|-----------------|
| **内存占用**        | 极低 (~0MB)      | 中等 (~14MB)    | 中等 (~14MB)    |
| **实现复杂度**      | 简单 (~20行)     | 中等 (~40行)    | 高 (~250行)     |
| **用户体验**        | 差 (无历史记录)   | 好 (保留元信息)  | 优秀 (分层清晰) |
| **风险**            | 低 (简单删除)     | 中 (空指针风险)  | 低 (架构清晰)   |
| **可维护性**        | 中 (逻辑简单)     | 低 (特殊情况多) | 高 (职责分离)   |
| **数据一致性**      | 高 (全删除)       | 中 (需手动同步) | 高 (自动同步)   |
| **可扩展性**        | 低 (难以扩展)     | 低 (特殊情况多) | 高 (易于扩展)   |

---

## 推荐方案

### 短期 (1-2天): 方案2 (最小修改)

**理由**:
- 快速修复内存泄漏
- 改善用户体验
- 风险可控

**适用场景**:
- 急需发布新版本
- 人力资源有限
- 不想大规模重构

### 长期 (1-2周): 方案1 (三层数据模型)

**理由**:
- 彻底解决架构问题
- 消除所有特殊情况
- 提高可维护性和可扩展性
- 符合 Linus 的 "好品味" 原则

**适用场景**:
- 有充足的开发时间
- 项目进入成熟期
- 计划长期维护

---

## Linus 的最终建议

> "Bad programmers worry about the code. Good programmers worry about data structures and their relationships."
> — Linus Torvalds

**当前问题**: 数据结构混乱 → 代码充满特殊情况

**正确做法**: 先设计数据结构 → 代码自然清晰

**具体建议**:

1. **立即修复**: 使用方案2 快速修复内存泄漏
2. **计划重构**: 在下一个迭代使用方案1 重新设计
3. **长期目标**: 分离关注点，让每个数据结构只做一件事

**Linus 式评价**:

```
折中策略: 🟡 凑合 (可以用,但不优雅)

致命问题:
- attackMap 永不清理 → 内存泄漏
- 数据分散在三处 → 不一致风险

改进方向:
"不要修补特殊情况,重新设计数据结构让特殊情况消失"

正确方案:
1. TestContext (测试期间) - 测完即丢
2. ScanResult (结果展示) - 仅元信息
3. VulnerabilityEvidence (证据保存) - 仅漏洞

这样写出来的代码:
- 无需判断 vulnType.isBlank()
- 无需判断 httpRequestResponse == null
- 无需手动清理内存
- GC 自动回收

"Keep it simple, stupid."
```

---

## 附录: 内存占用详细计算

### 假设场景

- 测试100个接口
- 每个接口5个参数
- 80%无漏洞, 20%有漏洞

### 当前实现 (无清理)

```
SourceLogEntry:
  - 100 × 1KB = 100KB

PocLogEntry:
  - 100 × 5 × 23 × 5KB = 57.5MB

总计: ~57.6MB
```

### 激进策略 (全删除)

```
SourceLogEntry: 0KB
PocLogEntry: 0KB
攻击: 0KB

总计: 0KB
```

### 折中策略 (方案2)

```
无漏洞 (80个):
  SourceLogEntry: 80 × 1KB = 80KB
  PocLogEntry (仅元信息): 80 × 5 × 23 × 0.2KB = 1.84MB

有漏洞 (20个):
  SourceLogEntry: 20 × 6KB = 120KB
  PocLogEntry (完整数据): 20 × 5 × 23 × 5KB = 11.5MB

总计: ~13.5MB
```

### 三层模型 (方案1)

```
ScanResult: 100 × 0.5KB = 50KB
TestMetadata: 100 × 5 × 23 × 0.2KB = 2.3MB
VulnerabilityEvidence: 20 × (6KB + 5×23×5KB) = 11.6MB

总计: ~14MB
```

---

## 总结

**折中策略的价值**: 在内存占用和用户体验之间找到平衡

**实施建议**:
1. **短期**: 方案2 (最小修改) - 快速修复内存泄漏
2. **长期**: 方案1 (三层数据模型) - 彻底解决架构问题

**核心原则**:

> "这10行可以变成3行"
> "数据结构错了,应该是..."
> — Linus Torvalds

不要修补特殊情况,重新设计数据结构。
