# Document Analysis Service（Python 文档解析服务）

基于 PyMuPDF + pdfplumber + python-docx + mistune3 + beautifulsoup4 的文档解析服务，输出有序、原子化的 Node JSON 序列，供 Java 侧 RAG 系统消费。是 Node-Based RAG 架构的**唯一文档解析入口**——所有文件类型（PDF/DOCX/Markdown/HTML/Code/LineBased）均在此解析为 Node 序列，Java 侧不再做按文件类型分派的处理。

## 职责

- **输入**：PDF / DOCX / Markdown / HTML / 各类源代码 / TXT/CSV/TSV/LOG 等行式文本（XLSX 暂未实现，返回空列表占位）
- **输出**：统一 Node JSON（`documentType` + `nodes[]`）
- **做**：结构识别（标题栈/titlePath）、超长块按句子内部拆分（共享 `groupId` 标识同源整块）、图片落盘、表格转 HTML
- **不做**：语义增强（VLM/LLM）、Chunk 装箱、向量化、存储——这些由 Java 侧负责

## Node JSON 协议

```json
{
  "documentType": "pdf",
  "nodes": [
    {"id": "n1", "type": "heading", "text": "...", "level": 1, "page": 1, "bbox": [...]},
    {"id": "n2", "type": "text", "text": "...", "page": 1, "bbox": [...]},
    {"id": "n3", "type": "image", "imagePath": "/chunk_images/1/101/img_001.png", "caption": "...", "page": 1, "bbox": [...]},
    {"id": "n4", "type": "code", "text": "...", "language": "java", "page": 1, "bbox": [...]},
    {"id": "n5", "type": "table", "html": "<table>...</table>", "rowCount": 5, "colCount": 4, "page": 2, "bbox": [...]}
  ]
}
```

## 目录结构

```
document_analysis_service/
├── app.py            # FastAPI 入口（/parse 接口，仅做文件类型校验后转发到 router）
├── parsers/          # 各类型解析器 + router 路由器（唯一派发入口）
│   ├── __init__.py   # 文本类解析器 eager import；PdfParser/DocxParser 懒加载（PEP 562）
│   ├── router.py     # detect_document_type + parse：类型判定 + 派发（pdf/docx 单例懒加载并注入图片目录）
│   ├── _common.py    # pdf/docx 共享工具（node id 生成、titlePath、图片哈希、代码语言识别、表格转 HTML）
│   ├── pdf_parser.py       # PDF（PyMuPDF + pdfplumber，需图片存储目录）
│   ├── docx_parser.py      # DOCX（python-docx，需图片存储目录）
│   ├── markdown_parser.py  # Markdown（mistune3 结构识别 + 手写领域逻辑）
│   ├── html_parser.py      # HTML（beautifulsoup4 DOM 遍历）
│   ├── code_parser.py      # 源代码（标准库 re）
│   └── linebased_parser.py # TXT/CSV/TSV/LOG 行式文本（标准库 re）
├── config.py         # 配置（从环境变量读取）
├── requirements.txt  # Python 依赖
└── README.md         # 本文件
```

所有解析器对外签名一致：`parse(file_path, document_id, user_id) -> List[Node dict]`，返回的 Node dict 协议与 Java 侧 `NodeDTO` 对应。各解析器层级平等，无父子关系；`router.py` 是唯一的派发入口。

## 环境准备

### 1. 选择 Python 环境

python3可用
```

### 2. 安装依赖

```bash
cd document_analysis_service
pip install -r requirements.txt
```

> PyMuPDF / pdfplumber / python-docx 等依赖首次使用时按需加载，无需额外下载模型。

## 启动服务

```bash
uvicorn app:app --host 0.0.0.0 --port 8000
```

或直接运行：

```bash
python app.py
```

## 配置项

通过环境变量配置（对应 Java 侧 `rag.python-service`）：

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| `SERVICE_HOST` | `0.0.0.0` | 监听地址 |
| `SERVICE_PORT` | `8000` | 监听端口 |
| `IMAGE_STORE_DIR` | `~/.linxing/chunk_images` | 图片存储根目录（应指向 Java 的 `storePath/chunk_images`） |
| `IMAGE_URL_PREFIX` | `/chunk_images` | 图片 URL 前缀 |
| `ENABLE_PICTURE_DESCRIPTION` | `false` | 保留项（历史占位，当前由 Java 侧 VLM 负责图片描述） |
| `LOG_LEVEL` | `INFO` | 日志级别 |

## 接口测试

### 健康检查

```bash
curl http://localhost:8000/health
# {"status":"ok"}
```

### 解析文档

```bash
curl -X POST http://localhost:8000/parse \
  -F "file=@test.pdf" \
  -F "documentId=101" \
  -F "userId=1"
```

## 与 Java 的协作

- Java 通过 `rag.python-service.url` 配置调用此服务（`DocumentAnalysisFacade` 优先调用 Python，失败时 fallback 到 `JavaDocumentAnalysisServiceImpl`，但 Java 备用方案当前尚未实现，调用会报错）
- 图片由 Python 直接保存到 Java 的 `storePath/chunk_images/{userId}/{docId}/`，Java 无需搬运
- Java 拿到 Node JSON 后，`NodeConverter` 反序列化为 `List<DocumentNode>`，进入 `SemanticEnhancementService`（VLM/LLM 语义增强）→ `NodeBasedChunkBuilder`（Node 装箱成 Chunk，含 groupId 父子装配）→ `ChunkIngestCoordinator` 责任链后处理（标题提取、向量化、全文索引）→ 持久化

## Node JSON 协议扩展字段

| 字段 | 说明 |
|------|------|
| `titlePath` | 标题路径（如「第一章 > 第一节」），由各 parser 维护标题栈推导；非标题块也带其所属标题路径，无标题上下文时为 null |
| `groupId` | 同源整块组 ID。超长 section/段落/方法在 parser 内部按句子拆为多个小 Node 时，这些子 Node 共享同一 `groupId`；普通块（未拆分）为 null。Java 侧 `NodeBasedChunkBuilder` 据 `groupId` 把同组子 Node 优先组装在一起，合成不可检索的 Level1 父块（同组 Node 拼接≈原整块）与多个可检索的 Level2 子块，建立父子关系 |
