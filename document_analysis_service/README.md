# Document Analysis Service（Python 文档解析服务）

基于 [Docling](https://github.com/docling-project/docling) 的文档解析服务，输出有序、原子化的 Node JSON 序列，供 Java 侧 RAG 系统消费。

> **设计文档**：`reference/TODOS/betterRAG/0701_addPython.md`

## 职责

- **输入**：PDF / DOCX / XLSX 文件
- **输出**：统一 Node JSON（`documentType` + `nodes[]`）
- **不做**：语义增强（VLM/LLM）、Chunk 构建、向量化、存储——这些由 Java 侧负责

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
├── app.py            # FastAPI 入口（/parse 接口）
├── parser.py         # Docling 封装 + Node 转换
├── config.py         # 配置（从环境变量读取）
├── requirements.txt  # Python 依赖
└── README.md         # 本文件
```

## 环境准备

### 1. 选择 Python 环境

python3可用
```

### 2. 安装依赖

```bash
cd document_analysis_service
pip install -r requirements.txt
```

> Docling 首次运行会自动下载模型（OCR、布局分析等），需联网。

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
| `ENABLE_PICTURE_DESCRIPTION` | `false` | 是否启用 Docling 内置图片描述（默认关闭，由 Java 侧 VLM 负责） |
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

- Java 通过 `rag.python-service.url` 配置调用此服务
- 图片由 Python 直接保存到 Java 的 `storePath/chunk_images/{userId}/{docId}/`，Java 无需搬运
- Java 拿到 Node JSON 后，反序列化为 `List<DocumentNode>`，进入语义增强 → ChunkBuilder → 持久化流程
