"""
FastAPI 入口模块

接口定义：
    POST /parse
    请求: multipart/form-data, 字段 file + documentId + userId
    响应: {"documentType": "pdf", "nodes": [...]}

启动方式:
    uvicorn app:app --host 0.0.0.0 --port 8000
"""

import os
import logging
import tempfile
from pathlib import Path

from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.responses import JSONResponse

from config import (
    HOST,
    PORT,
    IMAGE_STORE_DIR,
    IMAGE_URL_PREFIX,
    LOG_LEVEL,
)
from parser import DocumentParser

# 日志配置
logging.basicConfig(
    level=getattr(logging, LOG_LEVEL.upper(), logging.INFO),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("docling_analysis_service.app")

app = FastAPI(
    title="Document Analysis Service",
    description="基于 PyMuPDF + pdfplumber 的文档解析服务，输出有序、原子化的 Node JSON",
    version="1.0.0",
)

# 全局解析器实例
_parser: DocumentParser = None


def get_parser() -> DocumentParser:
    """懒加载全局 DocumentParser 单例"""
    global _parser
    if _parser is None:
        logger.info("初始化 DocumentParser...")
        _parser = DocumentParser(
            image_store_dir=IMAGE_STORE_DIR,
            image_url_prefix=IMAGE_URL_PREFIX,
        )
        logger.info("DocumentParser 初始化完成")
    return _parser


@app.get("/health")
async def health():
    """健康检查端点"""
    return {"status": "ok"}


@app.post("/parse")
async def parse(
    file: UploadFile = File(...),
    documentId: int = Form(...),
    userId: int = Form(...),
):
    """
    解析上传的文档，返回 Node JSON 序列

    :param file: 文档文件（PDF/DOCX）
    :param documentId: 文档 ID，用于图片目录隔离
    :param userId: 用户 ID，用于图片目录隔离
    :return: 统一 Node JSON
    """
    if not file.filename:
        raise HTTPException(status_code=400, detail="文件名不能为空")

    # 校验文件类型
    suffix = Path(file.filename).suffix.lower()
    if suffix not in (".pdf", ".docx", ".doc", ".xlsx"):
        raise HTTPException(
            status_code=400,
            detail=f"不支持的文件类型: {suffix}，仅支持 PDF/DOCX/XLSX",
        )

    logger.info(
        "收到解析请求: file=%s, documentId=%d, userId=%d",
        file.filename,
        documentId,
        userId,
    )

    # 保存到临时文件
    tmp_path = None
    try:
        content = await file.read()
        with tempfile.NamedTemporaryFile(
            delete=False, suffix=suffix, prefix="doc_"
        ) as tmp:
            tmp.write(content)
            tmp_path = tmp.name

        # 调用解析器
        parser = get_parser()
        result = parser.parse(tmp_path, documentId, userId)

        logger.info(
            "解析完成: file=%s, nodes=%d",
            file.filename,
            len(result.get("nodes", [])),
        )
        return JSONResponse(content=result)

    except HTTPException:
        raise
    except Exception as e:
        logger.exception("文档解析失败: %s", file.filename)
        raise HTTPException(status_code=500, detail=f"文档解析失败: {str(e)}")
    finally:
        # 清理临时文件
        if tmp_path and os.path.exists(tmp_path):
            try:
                os.unlink(tmp_path)
            except Exception:
                pass


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app:app", host=HOST, port=PORT, reload=False)
