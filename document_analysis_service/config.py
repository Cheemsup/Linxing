"""
配置模块

从环境变量读取配置，提供默认值。
配置项与 Java 侧 RagProperties.PythonService 对应。
"""

import os

# 服务监听配置
HOST = os.getenv("SERVICE_HOST", "0.0.0.0")
PORT = int(os.getenv("SERVICE_PORT", "8000"))

# 图片存储配置
# 对应 Java 侧 rag.store-path 下的 chunk_images 目录
# 默认值仅用于本地测试，生产环境应通过环境变量配置
# 默认与 Java 侧 rag.store-path (D:\JavaProjects\Linxing\files_store) 保持一致
IMAGE_STORE_DIR = os.getenv(
    "IMAGE_STORE_DIR",
    "D:/JavaProjects/Linxing/files_store/chunk_images",
)
IMAGE_URL_PREFIX = os.getenv("IMAGE_URL_PREFIX", "/chunk_images")

# 日志级别
LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO")
