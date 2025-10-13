# DetSql 数据保留策略深度分析报告

## 执行摘要

**用户理解验证结论**: ✅ **用户的理解是正确的**

经过对代码的深入分析，DetSql 的设计意图确实是一个**漏洞检测工具**，而不是覆盖率统计工具。用户提出的"只保留检测到漏洞的接口的全部数据"的理解符合工具的核心使用场景。

**推荐策略**: **策略A（激进策略）** - 只保留检测到漏洞的接口的全部数据

---

## 1. 用户理解验证

### 1.1 DetSql 的主要使用目的

通过分析代码和 UI 设计，DetSql 的核心目的是：

**✅ 主要目的：发现 SQL 注入漏洞**

证据：
- 扩展名称：`DetSql`（Detection SQL injection）
- 右键菜单选项："End this data"、"Send to DetSql" - 用于控制测试过程
- 表格列设计：`VulnState`（漏洞状态）列是核心字段
- 检测结果格式：`-errsql-stringsql-numsql`（只显示检测到的漏洞类型）

**❌ 不是主要目的：覆盖率统计**

证据：
- 没有"测试覆盖率"相关的 UI 元素
- 没有"已测试接口数量"统计功能
- 没有"测试完成度"指标

### 1.2 用户查看表格的场景分析

#### SourceTableModel（原始请求表）查看场景

**代码证据**（DetSql.java:505-524）：
```java
table1 = new JTable(tableModel) {
    @Override
    public void changeSelection(int rowIndex, int columnIndex, boolean toggle, boolean extend) {
        SourceLogEntry logEntry = tableModel.get(DetSql.table1.convertRowIndexToModel(rowIndex));
        if (logEntry.getHttpRequestResponse() != null) {
            requestViewer.setRequest(logEntry.getHttpRequestResponse().request());
            responseViewer.setResponse(logEntry.getHttpRequestResponse().response());
            // ...
            List<PocLogEntry> pocLogEntries = myHttpHandler.attackMap.get(sm3Hash);
            pocTableModel.add(pocLogEntries);  // 显示 PoC 测试结果
        }
    }
};
```

**用户操作流程**：
1. 用户点击 SourceTableModel 中的某一行
2. 系统加载该行对应的 `HttpRequestResponse`
3. 系统从 `attackMap` 中获取该请求的所有 PoC 测试结果
4. 系统更新 PocTableModel 显示测试详情

**关键发现**：
- 只有 `httpRequestResponse != null` 的行才能查看详情
- 没有漏洞的行，`httpRequestResponse` 是 `null`（代码第293行）

```java
// MyHttpHandler.java:293-296
HttpRequestResponse.httpRequestResponse(
    response.initiatingRequest(),
    HttpResponse.httpResponse(response.toByteArray())  // 只有漏洞行才保存响应
)
```

#### PocTableModel（PoC测试结果表）查看场景

**代码证据**（DetSql.java:624-636）：
```java
table2 = new JTable(pocTableModel) {
    @Override
    public void changeSelection(int rowIndex, int columnIndex, boolean toggle, boolean extend) {
        PocLogEntry logEntry = pocTableModel.get(DetSql.table2.convertRowIndexToModel(rowIndex));
        if (logEntry.getHttpRequestResponse() != null) {
            requestViewer.setRequest(logEntry.getHttpRequestResponse().request());
            responseViewer.setResponse(logEntry.getHttpRequestResponse().response());
            super.changeSelection(rowIndex, columnIndex, toggle, extend);
        }
    }
};
```

**用户操作流程**：
1. 用户点击 PocTableModel 中的某个测试结果
2. 系统显示该 PoC 的请求和响应详情
3. 用户验证该 PoC 是否真的存在漏洞

**关键发现**：
- PocTableModel 只显示有漏洞的接口的测试结果
- `attackMap` 存储了每个请求的所有测试结果

### 1.3 UI 右键菜单功能分析

**代码证据**（DetSql.java:556-590）：

**菜单1: "delete selected rows"**
```java
menuItem1.addActionListener(e -> {
    int[] selectedRows = table1.getSelectedRows();
    for (int i = selectedRows.length - 1; i >= 0; i--) {
        if (!sourceTableModel.getValueAt(..., 6).equals("run")) {  // 不删除正在运行的
            sourceTableModel.log.remove(...);
            tableModel.fireTableRowsDeleted(selectedRows[i], selectedRows[i]);
        }
    }
});
```

**菜单2: "delete novuln history"**
```java
menuItem2.addActionListener(e -> {
    for (int i = sourceTableModel.log.size() - 1; i >= 0; i--) {
        if (sourceTableModel.getValueAt(..., 6).equals("")    // VulnState 为空（无漏洞）
            || sourceTableModel.getValueAt(..., 6).equals("手动停止")) {  // 或被手动停止
            sourceTableModel.log.remove(...);
            tableModel.fireTableRowsDeleted(i, i);
        }
    }
});
```

**关键发现**：
- 用户有专门的菜单项 **"删除无漏洞历史"**
- 这证明了用户不需要长期保留无漏洞的数据
- 用户只关心有漏洞的接口

### 1.4 VulnState 列的设计意图

**代码证据**（MyHttpHandler.java:282-307）：

```java
private void updateLogEntry(HttpResponseReceived response, String hash,
                           int logIndex, String vulnType) {
    // ...
    SwingUtilities.invokeLater(() -> {
        SourceLogEntry newEntry = new SourceLogEntry(
            finalLogIndex,
            response.toolSource().toolType().toolName(),
            hash,
            finalVulnType,
            response.bodyToString().length(),
            vulnType.isBlank() ? null : HttpRequestResponse.httpRequestResponse(...),  // 关键逻辑
            // ...
        );
        // ...
    });
}
```

**VulnState 的可能值**：
- `"run"` - 正在测试
- `""` - 测试完成，无漏洞
- `"手动停止"` - 用户手动停止
- `"-errsql-stringsql-numsql-ordersql-boolsql-diypoc"` - 检测到的漏洞类型

**关键发现**：
- 当 `vulnType.isBlank()` 时，`httpRequestResponse` 设置为 `null`
- 这意味着无漏洞的行**无法查看请求/响应详情**
- 设计意图明确：无漏洞的数据不需要保留完整信息

---

## 2. 使用场景分析

### 场景A：安全测试人员寻找漏洞 ⭐⭐⭐⭐⭐

**频率**: 高（主要场景）

**需要的数据**: 只需要检测到漏洞的接口

**不需要的数据**: 未检测到漏洞的接口

**支持证据**:
- UI 设计没有覆盖率统计功能
- 右键菜单提供"删除无漏洞历史"选项
- VulnState 为空的行无法查看详情

**结论**: ✅ 用户理解正确，这是 DetSql 的核心使用场景

---

### 场景B：手动验证和调试 ⭐⭐

**频率**: 中（辅助场景）

**需要的数据**: 包括"未检测到漏洞"的接口的测试过程

**理由**: 可能是误判（假阴性）

**代码支持**:
- 右键菜单 "Send to DetSql" 支持重新测试
- Repeater 模块的请求可以重复测试（不去重）

**分析**:

**当前实现的支持方式**:
1. 用户可以右键点击任何请求
2. 选择 "Send to DetSql" 重新测试
3. 使用不同的 Payload 或配置

**数据保留的必要性**:
- ❌ 不需要保留"无漏洞"的完整数据
- ✅ 只需要保留 URL、参数名、Method 等元数据
- ✅ 用户可以从 Burp Suite 的历史记录中找到原始请求

**结论**: ❌ 不需要保留完整的请求/响应数据

---

### 场景C：覆盖率统计 ⭐

**频率**: 低（DetSql 不支持此功能）

**需要的数据**: 所有接口的测试记录（包括无漏洞的）

**理由**: 证明测试覆盖面

**代码证据**:
- UI 没有"测试覆盖率"相关组件
- 没有"已测试接口数量"统计
- 没有"测试完成度"指标

**分析**:

如果 DetSql 要支持覆盖率统计，应该有这些功能：
- "已测试接口：100/200"
- "测试覆盖率：50%"
- "按域名分组统计"

但实际代码中**没有任何这些功能**。

**结论**: ❌ DetSql 不是覆盖率统计工具，不需要为此保留数据

---

### 场景D：重新测试 ⭐⭐⭐

**频率**: 中（通过 Repeater/右键菜单实现）

**需要的数据**: 原始请求信息

**当前实现**:

**代码证据**（DetSql.java:463-469）:
```java
menuItem3.addActionListener(new ActionListener() {
    @Override
    public void actionPerformed(ActionEvent e) {
        HttpRequestResponse selectHttpRequestResponse = event.messageEditorRequestResponse().get().requestResponse();
        myHttpHandler.createProcessThread(selectHttpRequestResponse);
    }
});
```

**分析**:
1. 用户可以右键点击任何请求（包括无漏洞的）
2. 选择 "Send to DetSql" 重新测试
3. Burp Suite 会保留原始请求数据

**关键发现**:
- ✅ Burp Suite 本身会保留所有 HTTP 历史记录
- ✅ 用户可以从 Burp 的历史记录中重新测试
- ❌ DetSql 不需要重复保留这些数据

**结论**: ❌ 不需要在 DetSql 中保留完整的无漏洞数据

---

## 3. 数据保留策略对比

### 策略A：激进策略（用户建议）⭐⭐⭐⭐⭐

**描述**: 只保留检测到漏洞的接口的全部数据，未检测到漏洞的接口完全删除

**实现方式**:

```java
// MyHttpHandler.java:updateLogEntry() 修改
private void updateLogEntry(HttpResponseReceived response, String hash,
                           int logIndex, String vulnType) {
    if (vulnType.isBlank()) {
        // 完全删除此请求的数据
        attackMap.remove(hash);

        // 从 SourceTableModel 中删除
        SwingUtilities.invokeLater(() -> {
            int rowIndex = sourceTableModel.log.indexOf(
                new SourceLogEntry(logIndex, null, null, null, 0, null, null, null, null)
            );
            if (rowIndex >= 0) {
                sourceTableModel.log.remove(rowIndex);
                sourceTableModel.fireTableRowsDeleted(rowIndex, rowIndex);
            }
        });
    } else {
        // 保留完整数据（当前实现）
        SwingUtilities.invokeLater(() -> {
            // ...
        });
    }
}
```

**内存优化效果**:

假设测试 1000 个接口，其中 50 个有漏洞：

- **当前实现**:
  - SourceTableModel: 1000 行 × 500 字节 ≈ 500 KB
  - attackMap: 1000 个 entry × 10 KB（每个请求的所有测试结果）≈ 10 MB
  - 总计: **~10.5 MB**

- **策略A**:
  - SourceTableModel: 50 行 × 500 字节 ≈ 25 KB
  - attackMap: 50 个 entry × 10 KB ≈ 500 KB
  - 总计: **~525 KB**

- **内存节省**: **95% 内存节省**

**用户体验影响**:
- ✅ 表格更简洁，只显示有漏洞的接口
- ✅ 符合工具的核心使用场景
- ✅ 减少内存占用，提升性能
- ✅ 与右键菜单 "delete novuln history" 的意图一致

**风险评估**:
- ❌ 无法查看"已测试但无漏洞"的接口列表
- ✅ 但可以从 Burp Suite 历史记录中查看
- ✅ 用户可以随时右键 "Send to DetSql" 重新测试

**结论**: ⭐⭐⭐⭐⭐ **强烈推荐**

---

### 策略B：折中策略 ⭐⭐⭐

**描述**: 检测到漏洞的保留全部数据，未检测到漏洞的保留元数据（URL、参数名、测试次数），清理响应体

**实现方式**:

```java
// MyHttpHandler.java:updateLogEntry() 修改
private void updateLogEntry(HttpResponseReceived response, String hash,
                           int logIndex, String vulnType) {
    if (vulnType.isBlank()) {
        // 保留元数据，清理响应体
        SwingUtilities.invokeLater(() -> {
            SourceLogEntry newEntry = new SourceLogEntry(
                finalLogIndex,
                response.toolSource().toolType().toolName(),
                hash,
                "",
                0,  // bodyLength 设置为 0
                null,  // httpRequestResponse 设置为 null
                response.initiatingRequest().httpService().toString(),
                response.initiatingRequest().method(),
                response.initiatingRequest().pathWithoutQuery()
            );
            // ...
        });

        // 清理 attackMap 中的测试结果
        attackMap.remove(hash);
    }
}
```

**内存优化效果**:

假设测试 1000 个接口，其中 50 个有漏洞：

- **当前实现**: ~10.5 MB
- **策略B**:
  - SourceTableModel: 1000 行 × 100 字节（只保留元数据）≈ 100 KB
  - attackMap: 50 个 entry × 10 KB ≈ 500 KB
  - 总计: **~600 KB**

- **内存节省**: **94% 内存节省**

**用户体验影响**:
- ✅ 可以查看"已测试但无漏洞"的接口列表
- ❌ 但无法查看这些接口的详细测试结果
- ✅ 可以右键 "Send to DetSql" 重新测试

**风险评估**:
- ✅ 保留了覆盖率信息
- ❌ 但 DetSql 本身不支持覆盖率统计功能

**结论**: ⭐⭐⭐ 可以接受，但过于复杂

---

### 策略C：保守策略（当前实现） ⭐

**描述**: 保留所有数据

**内存占用**: ~10.5 MB（1000 个接口的测试）

**用户体验影响**:
- ❌ 表格混乱，充斥着无漏洞的记录
- ❌ 用户需要手动 "delete novuln history"
- ❌ 内存占用高

**结论**: ⭐ 不推荐

---

### 策略D：可配置策略 ⭐⭐

**描述**: 提供配置选项让用户选择策略A/B/C

**实现方式**:
- 在 Config 标签页添加 Checkbox: "只保留有漏洞的数据"

**用户体验影响**:
- ✅ 灵活
- ❌ 增加配置复杂度
- ❌ 大部分用户不知道如何选择

**结论**: ⭐⭐ 不必要的复杂度

---

## 4. 推荐方案：策略A（激进策略）

### 4.1 推荐理由

1. **符合工具的核心使用场景**
   - DetSql 是漏洞检测工具，不是覆盖率统计工具
   - 用户只关心有漏洞的接口

2. **与现有 UI 设计一致**
   - 右键菜单已经提供 "delete novuln history"
   - VulnState 为空的行无法查看详情

3. **内存优化效果显著**
   - 节省 95% 内存
   - 提升性能

4. **不影响用户工作流程**
   - 用户可以从 Burp Suite 历史记录中重新测试
   - 右键菜单 "Send to DetSql" 随时可用

### 4.2 详细实现方案

#### Step 1: 修改 `updateLogEntry()` 方法

**位置**: `MyHttpHandler.java:279-307`

```java
private void updateLogEntry(HttpResponseReceived response, String hash,
                           int logIndex, String vulnType) {
    final int finalLogIndex = logIndex;
    final String finalVulnType = vulnType;

    if (vulnType.isBlank()) {
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 无漏洞：完全删除数据
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

        // 1. 从 attackMap 中删除
        attackMap.remove(hash);

        // 2. 从 SourceTableModel 中删除
        SwingUtilities.invokeLater(() -> {
            int rowIndex = sourceTableModel.log.indexOf(
                new SourceLogEntry(finalLogIndex, null, null, null, 0, null, null, null, null)
            );

            if (rowIndex >= 0) {
                sourceTableModel.log.remove(rowIndex);
                sourceTableModel.fireTableRowsDeleted(rowIndex, rowIndex);
            }
        });
    } else {
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 有漏洞：保留完整数据（当前实现）
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        SwingUtilities.invokeLater(() -> {
            SourceLogEntry newEntry = new SourceLogEntry(
                finalLogIndex,
                response.toolSource().toolType().toolName(),
                hash,
                finalVulnType,
                response.bodyToString().length(),
                HttpRequestResponse.httpRequestResponse(
                    response.initiatingRequest(),
                    HttpResponse.httpResponse(response.toByteArray())
                ),
                response.initiatingRequest().httpService().toString(),
                response.initiatingRequest().method(),
                response.initiatingRequest().pathWithoutQuery()
            );

            int rowIndex = sourceTableModel.log.indexOf(
                new SourceLogEntry(finalLogIndex, null, null, null, 0, null, null, null, null)
            );
            sourceTableModel.updateVulnState(newEntry, rowIndex, rowIndex);
        });
    }
}
```

#### Step 2: 同步修改 `processRequestWithSemaphore()` 方法

**位置**: `MyHttpHandler.java:1746-1875`

```java
private void processRequestWithSemaphore(
    HttpRequestResponse httpRequestResponse,
    Semaphore sem
) throws InterruptedException {
    // ... (前面的代码保持不变)

    try {
        String oneVuln = "";
        sem.acquire();
        try {
            if (!Thread.currentThread().isInterrupted()) {
                oneVuln = processManualResponse(requestSm3Hash, httpRequestResponse);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            sem.release();
            final int finalOneLogSize = oneLogSize;
            final String finalOneVuln = oneVuln;

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 修改：无漏洞时删除，有漏洞时更新
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            if (oneVuln.isBlank()) {
                // 完全删除
                attackMap.remove(requestSm3Hash);
                SwingUtilities.invokeLater(() -> {
                    int rowIndex = sourceTableModel.log.indexOf(
                        new SourceLogEntry(finalOneLogSize, null, null, null, 0, null, null, null, null)
                    );
                    if (rowIndex >= 0) {
                        sourceTableModel.log.remove(rowIndex);
                        sourceTableModel.fireTableRowsDeleted(rowIndex, rowIndex);
                    }
                });
            } else {
                // 保留完整数据
                SwingUtilities.invokeLater(() -> {
                    sourceTableModel.updateVulnState(
                        new SourceLogEntry(
                            finalOneLogSize,
                            "Send",
                            requestSm3Hash,
                            finalOneVuln,
                            httpRequestResponse.response().bodyToString().length(),
                            httpRequestResponse,
                            httpRequestResponse.request().httpService().toString(),
                            httpRequestResponse.request().method(),
                            httpRequestResponse.request().pathWithoutQuery()
                        ),
                        sourceTableModel.log.indexOf(
                            new SourceLogEntry(finalOneLogSize, null, null, null, 0, null, null, null, null)
                        ),
                        sourceTableModel.log.indexOf(
                            new SourceLogEntry(finalOneLogSize, null, null, null, 0, null, null, null, null)
                        )
                    );
                });
            }
        }
    } catch (InterruptedException e) {
        // 手动停止：删除数据
        attackMap.remove(requestSm3Hash);
        final int finalOneLogSize = oneLogSize;
        SwingUtilities.invokeLater(() -> {
            int rowIndex = sourceTableModel.log.indexOf(
                new SourceLogEntry(finalOneLogSize, null, null, null, 0, null, null, null, null)
            );
            if (rowIndex >= 0) {
                sourceTableModel.log.remove(rowIndex);
                sourceTableModel.fireTableRowsDeleted(rowIndex, rowIndex);
            }
        });
        throw e;
    }
}
```

#### Step 3: 移除 "delete novuln history" 菜单项

**位置**: `DetSql.java:558-590`

**理由**: 不再需要手动删除，因为无漏洞的数据会自动删除

```java
// 删除这个菜单项
// menuItem2: "delete novuln history"
```

#### Step 4: 更新用户文档

**说明**:
- DetSql 现在只显示检测到漏洞的接口
- 如果需要重新测试某个接口，右键选择 "Send to DetSql"

### 4.3 风险评估和缓解措施

| 风险 | 影响 | 缓解措施 | 严重性 |
|------|------|----------|--------|
| 用户无法查看"已测试但无漏洞"的接口列表 | 无法统计测试覆盖率 | Burp Suite 的 HTTP History 可以查看所有请求 | 低 |
| 误删除有价值的数据 | 无法恢复 | 用户可以右键 "Send to DetSql" 重新测试 | 低 |
| 用户习惯改变 | 需要适应新的工作流程 | 更新文档和 README | 低 |

### 4.4 测试计划

1. **功能测试**:
   - 测试无漏洞的接口是否被正确删除
   - 测试有漏洞的接口是否被正确保留
   - 测试右键菜单 "Send to DetSql" 是否正常工作

2. **内存测试**:
   - 测试 1000 个接口，观察内存占用
   - 对比当前实现和新实现的内存占用

3. **UI 测试**:
   - 测试表格更新是否流畅
   - 测试删除操作是否引起 UI 闪烁

---

## 5. 总结

### 5.1 核心结论

✅ **用户的理解是正确的**

DetSql 的设计意图是一个**漏洞检测工具**，核心使用场景是**发现 SQL 注入漏洞**，而不是覆盖率统计或手动验证工具。

### 5.2 推荐策略

⭐⭐⭐⭐⭐ **策略A（激进策略）**

**只保留检测到漏洞的接口的全部数据，未检测到漏洞的接口完全删除**

**优点**:
- 内存节省 95%
- 表格更简洁
- 符合工具的核心使用场景
- 与现有 UI 设计一致

**缺点**:
- 无法查看"已测试但无漏洞"的接口列表
- 但可以从 Burp Suite 历史记录中查看

### 5.3 实施建议

1. **立即实施**: 修改 `updateLogEntry()` 和 `processRequestWithSemaphore()` 方法
2. **移除冗余功能**: 删除 "delete novuln history" 菜单项
3. **更新文档**: 说明新的数据保留策略
4. **测试**: 确保功能正常，内存优化生效

### 5.4 后续优化

如果未来需要支持覆盖率统计，可以：
1. 添加一个新的 UI 标签页 "Statistics"
2. 统计"已测试接口数量"、"检测到漏洞数量"
3. 不需要保留完整的无漏洞数据，只需要计数器

---

## 附录：关键代码位置

| 功能 | 文件 | 行号 |
|------|------|------|
| 更新日志条目 | MyHttpHandler.java | 279-307 |
| 处理请求（Repeater） | MyHttpHandler.java | 1746-1875 |
| 删除无漏洞历史（菜单） | DetSql.java | 579-590 |
| 表格选择（查看详情） | DetSql.java | 505-524 |
| VulnState 列更新 | SourceTableModel.java | 87-90 |
| attackMap 数据结构 | MyHttpHandler.java | 78 |

---

## 作者

Linus Torvalds (模拟)

**评价**: 这个数据保留策略问题是个好例子，说明了**数据结构决定一切**。attackMap 的设计本身就暗示了工具的意图 - 它存储的是 "攻击测试结果"，不是 "测试覆盖率报告"。代码已经告诉了你答案，用户的理解是正确的。

**建议**: 删除无用的数据，简化代码。如果未来需要覆盖率统计，那是另一个问题，不要为假想的需求保留数据。
