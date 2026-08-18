"""
配置模块

从环境变量读取配置，提供默认值。
配置项与 Java 侧 RagProperties.PythonService 对应。
"""

import os

# 服务监听配置
HOST = os.getenv("SERVICE_HOST", "0.0.0.0")
# 本机 8000 落在 Hyper-V/WSL 保留端口段（netsh interface ipv4 show excludedportrange 可见），bind 会报 Errno 13，
# 故默认改用 18000；如需其他端口可用环境变量 SERVICE_PORT 覆盖（Java 侧 rag.python-service.url 需同步）。
PORT = int(os.getenv("SERVICE_PORT", "18000"))

# 图片存储配置
# 对应 Java 侧 rag.store-path 下的 chunk_images 目录
# 默认值仅用于本地测试，生产环境应通过环境变量配置
# 默认与 Java 侧 rag.store-path保持一致
IMAGE_STORE_DIR = os.getenv(
    "IMAGE_STORE_DIR",
    "D:/JavaProjects/Linxing/files_store/chunk_images",
)
IMAGE_URL_PREFIX = os.getenv("IMAGE_URL_PREFIX", "/chunk_images")

# 日志级别
LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO")

# ============================== MinerU 云托管解析（PDF） ==============================
# 官方 v4 API（https://mineru.net/apiManage/docs），Bearer token 鉴权：
#   POST /api/v4/file-urls/batch          申请上传 URL（返回 batch_id + file_urls）
#   PUT  <file_urls[0]>                    上传裸字节（自动提交解析）
#   GET  /api/v4/extract-results/batch/{id} 轮询结果（done 后得 full_zip_url）
# 结果 zip 内含 full.md / images/ / *_content_list.json / *_middle.json。
# 未配置 API key 时 PDF 走本地 PyMuPDF 兜底（见 parsers/pdf_parser.py）。
MINERU_API_KEY = os.getenv("MINERU_API_KEY", "")
# 正确 host 是 mineru.net（api.mineru.net 解析不可达，已实测）
MINERU_BASE_URL = os.getenv("MINERU_BASE_URL", "https://mineru.net")
# 模型版本：文档用 vlm；HTML 用 MinerU-HTML
MINERU_MODEL_VERSION = os.getenv("MINERU_MODEL_VERSION", "vlm")
# 结果轮询间隔（秒）
MINERU_POLL_INTERVAL = int(os.getenv("MINERU_POLL_INTERVAL", "3"))
# 云端处理超时（上传+轮询+下载），默认 480s；须小于 Java 侧 rag.python-service.timeout-seconds(600s)，
# 以给"云端超时后回退本地 PyMuPDF"留足余量
MINERU_TIMEOUT_SECONDS = int(os.getenv("MINERU_TIMEOUT_SECONDS", "480"))
# MinerU 文件大小上限（官方 200MB / 200 页），超限直接走本地兜底不硬失败
MINERU_MAX_FILE_MB = int(os.getenv("MINERU_MAX_FILE_MB", "200"))
