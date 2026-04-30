# 文档树形结构改进计划

## 一、前端展示优化

### 1.1 扁平结构分组显示
- 对于 TXT/CSV 等扁平结构文件，可考虑按内容特征分组显示
- 例如：按段落主题、按关键词聚类

### 1.2 分块类型图标
- 添加分块类型图标区分不同类型节点
- 图标建议：
  - 📄 general - 普通文本
  - 💻 code - 代码块
  - 📊 table - 表格
  - 📝 section - 章节

### 1.3 类型筛选功能
- 支持按类型筛选节点
- 支持只显示代码块/表格等特定类型

## 二、树形结构增强

### 2.1 保持原始文件的内容顺序
- 分块在树中显示顺序与原始文件中顺序一致
- 避免分块在树中显示混乱，例如第一章在第二章前面

### 2.2 节点顺序号
- 添加节点在同级中的位置序号
- 格式：`1.1.2` 表示第1个L1节点下的第1个L2节点下的第2个L3节点

### 2.3 字数统计
- 显示每个分块的字数/字符数
- 帮助用户快速了解分块大小

### 2.4 状态持久化
- 支持节点展开/折叠状态持久化
- 使用 localStorage 保存用户偏好

## 三、上下文定位增强

### 3.1 原文跳转
- 支持从树节点跳转到原文位置
- 需要记录分块在原文中的起始位置

### 3.2 高亮显示
- 支持高亮显示当前选中的分块
- 在预览面板中同步滚动到对应位置

### 3.3 跨文档关联
- 支持跨文档的分块关联
- 基于语义相似度推荐相关分块

## 四、数据结构扩展

### 4.1 ChunkTreeVO 扩展字段
```java
private Integer orderInLevel;      // 在同级中的顺序号
private Integer charCount;         // 字符数
private Integer startPosition;     // 在原文中的起始位置
private Integer endPosition;       // 在原文中的结束位置
```

### 4.2 新增统计信息
```java
public class DocumentTreeStats {
    private Integer totalChunks;
    private Integer maxDepth;
    private Map<String, Integer> typeDistribution;
    private Integer totalCharacters;
}
```

## 五、优先级排序

| 优先级 | 功能 | 工作量 |
|--------|------|--------|
| P1 | 类型图标 | 小 |
| P1 | 字数统计 | 小 |
| P2 | 节点顺序号 | 中 |
| P2 | 高亮显示 | 中 |
| P3 | 扁平结构分组 | 大 |
| P3 | 原文跳转 | 大 |
| P3 | 跨文档关联 | 大 |
