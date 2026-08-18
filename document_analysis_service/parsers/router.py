"""
文档类型路由器

职责：根据文件扩展名 + 内容特征二次判定文档类型，分发给对应的解析器；是 python 侧
唯一的解析派发入口（2026-07-11 第 21 点下沉后，pdf/docx 与 md/html/code/linebased
平等，均由本模块直接派发）。

返回 {"documentType": str, "nodes": List[dict]}。

说明：
- pdf / docx 解析器需要图片存储目录，由本模块从 config 读取 IMAGE_STORE_DIR /
  IMAGE_URL_PREFIX 后懒加载注入；import config 只读环境变量，不会强制加载
  fitz/pdfplumber/python-docx，重型依赖仍是首次解析 pdf/docx 时才加载。
- md / html / code / linebased 解析器无图片需求，全局单例直接派发。
- xlsx 暂未实现独立 parser，保留占位（warning + 空列表）。
"""

import logging
from pathlib import Path
from typing import Any, Dict

from config import (
    IMAGE_STORE_DIR,
    IMAGE_URL_PREFIX,
    MINERU_API_KEY,
    MINERU_BASE_URL,
    MINERU_MODEL_VERSION,
    MINERU_POLL_INTERVAL,
    MINERU_TIMEOUT_SECONDS,
    MINERU_MAX_FILE_MB,
)
from .linebased_parser import LineBasedParser
from .code_parser import CodeParser
from .markdown_parser import MarkdownParser

logger = logging.getLogger("docling_analysis_service.parsers.router")

# 各类型解析器单例；pdf/docx/markdown 需注入图片目录（markdown 文档自带本地图片需落盘），
# 单例在首次解析时懒加载并注入 image 配置
_markdown_parser = None
_html_parser = None
_code_parser = None
_linebased_parser = None
_pdf_parser = None
_docx_parser = None


def _get_markdown_parser():
    """懒加载 Markdown 解析器单例，注入图片存储目录配置。

    markdown 文档自带本地图片资源需落盘（与 docx/pdf 一致），故注入 IMAGE_STORE_DIR /
    IMAGE_URL_PREFIX；远程 http(s) 图片不下载，仅本地图片处理。
    """
    global _markdown_parser
    if _markdown_parser is None:
        from .markdown_parser import MarkdownParser
        _markdown_parser = MarkdownParser(
            image_store_dir=IMAGE_STORE_DIR,
            image_url_prefix=IMAGE_URL_PREFIX,
        )
    return _markdown_parser


def _get_html_parser():
    global _html_parser
    if _html_parser is None:
        # 延迟导入，bs4 仅在解析 HTML 时才需要
        from .html_parser import HtmlParser
        _html_parser = HtmlParser()
    return _html_parser


def _get_code_parser():
    global _code_parser
    if _code_parser is None:
        _code_parser = CodeParser()
    return _code_parser


def _get_linebased_parser():
    global _linebased_parser
    if _linebased_parser is None:
        _linebased_parser = LineBasedParser()
    return _linebased_parser


def _get_pdf_parser():
    """懒加载 PDF 解析器单例，注入图片存储目录 + MinerU 云客户端。

    配置了 MINERU_API_KEY 时构造 MineruClient 注入 PdfParser（MinerU 为主路径，
    失败/未配置回退本地 PyMuPDF）。延迟导入 fitz/pdfplumber，避免未解析 pdf 时
    也强制加载这些重型依赖。
    """
    global _pdf_parser
    if _pdf_parser is None:
        from .pdf_parser import PdfParser

        mineru_client = None
        if MINERU_API_KEY:
            from .mineru_client import MineruClient
            mineru_client = MineruClient(
                api_key=MINERU_API_KEY,
                base_url=MINERU_BASE_URL,
                model_version=MINERU_MODEL_VERSION,
                poll_interval=MINERU_POLL_INTERVAL,
                timeout_seconds=MINERU_TIMEOUT_SECONDS,
            )
            logger.info("PdfParser 启用 MinerU 云解析: model=%s, base=%s",
                        MINERU_MODEL_VERSION, MINERU_BASE_URL)

        _pdf_parser = PdfParser(
            image_store_dir=IMAGE_STORE_DIR,
            image_url_prefix=IMAGE_URL_PREFIX,
            mineru_client=mineru_client,
            mineru_max_size_mb=MINERU_MAX_FILE_MB,
        )
    return _pdf_parser


def _get_docx_parser():
    """懒加载 DOCX 解析器单例，注入图片存储目录配置。

    延迟导入 python-docx，避免未解析 docx 时也强制加载这些重型依赖。
    """
    global _docx_parser
    if _docx_parser is None:
        from .docx_parser import DocxParser
        _docx_parser = DocxParser(
            image_store_dir=IMAGE_STORE_DIR,
            image_url_prefix=IMAGE_URL_PREFIX,
        )
    return _docx_parser


# 扩展名 → 文档类型映射（与旧 Java ChunkStrategy.supports() 的扩展名判定对齐）
_MARKDOWN_EXTS = {"md", "markdown"}
_HTML_EXTS = {"html", "htm"}
_CODE_EXTS = {
    "java", "py", "js", "ts", "go", "rs", "c", "cpp", "cs", "kt",
    "rb", "php", "swift", "scala", "hs", "lua", "r", "sh", "bash", "sql",
}
_LINEBASED_EXTS = {"log", "csv", "tsv", "txt"}

# 代码内容特征指示符（与旧 Java CodeChunkStrategy.CODE_INDICATOR 一致）
_CODE_INDICATOR_KEYWORDS = (
    "package", "import", "class", "public class", "private class",
    "def ", "function ", "fn ", "func ", "int main", "void main",
)


def detect_document_type(file_path) -> str:
    """
    按扩展名 + 内容特征二次判定文档类型。

    判定顺序：
    1. 扩展名直接命中结构化类型（pdf/docx/xlsx）→ 返回对应类型（由本模块 parse 派发到 pdf/docx parser）
    2. 扩展名命中 markdown/html/code/linebased → 返回对应类型
    3. 扩展名未命中 → 读内容特征：
       - 含 `# `/`## `/` ``` ` → markdown
       - 含 `<html`/`<body`/`<div` → html
       - 含 package/class/def/function 等代码指示符 → code
       - 行长度方差小 / 频繁空行块 → linebased
       - 否则 fallback 到 linebased（最通用的文本兜底）

    :param file_path: 文件路径（str 或 Path）
    :return: 文档类型字符串（pdf/docx/xlsx/markdown/html/code/linebased）
    """
    suffix = Path(file_path).suffix.lower().lstrip(".")
    file_path = Path(file_path)

    # 1. 结构化文档（由本模块 parse 派发到 pdf/docx parser）
    if suffix == "pdf":
        return "pdf"
    if suffix in ("docx", "doc"):
        return "docx"
    if suffix == "xlsx":
        return "xlsx"

    # 2. 扩展名直接命中
    if suffix in _MARKDOWN_EXTS:
        return "markdown"
    if suffix in _HTML_EXTS:
        return "html"
    if suffix in _CODE_EXTS:
        return "code"
    if suffix in _LINEBASED_EXTS:
        return "linebased"

    # 3. 内容特征二次判定
    sample = _read_sample(file_path)
    if sample:
        if _looks_like_markdown(sample):
            return "markdown"
        if _looks_like_html(sample):
            return "html"
        if _looks_like_code(sample):
            return "code"

    # linebased 作为最通用的文本兜底（与 Java RecursiveChunkStrategy 兜底语义对应）
    return "linebased"


# ------------------------------ 内容特征判定 ------------------------------

def _read_sample(file_path: Path, max_chars: int = 500) -> str:
    """读取文件前若干字符用于内容特征判定（多编码兜底）。"""
    for encoding in ("utf-8", "gbk", "latin-1"):
        try:
            with open(file_path, "r", encoding=encoding) as f:
                return f.read(max_chars)
        except (UnicodeDecodeError, OSError):
            continue
    return ""


def _looks_like_markdown(sample: str) -> bool:
    """Markdown 内容特征：含 `# `/`## `/` ``` `（与旧 Java MarkdownChunkStrategy.supports 一致）。"""
    if "# " in sample or "## " in sample or "```" in sample:
        return True
    return sample.lstrip().startswith("#")


def _looks_like_html(sample: str) -> bool:
    """HTML 内容特征：含典型 HTML 标签。"""
    lower = sample.lower()
    return ("<html" in lower or "<body" in lower or "<div" in lower
            or "<head" in lower or "<!doctype html" in lower)


def _looks_like_code(sample: str) -> bool:
    """代码内容特征：含 package/import/class/def/function 等指示符（与旧 Java CODE_INDICATOR 一致）。"""
    return any(kw in sample for kw in _CODE_INDICATOR_KEYWORDS)



def parse(file_path, document_id: int, user_id: int) -> Dict[str, Any]:
    """
    路由分发：按检测到的文档类型调用对应解析器，返回统一 Node JSON。

    :param file_path: 文件路径
    :param document_id: 文档 ID（md/html/code/linebased 无图片，仅透传）
    :param user_id: 用户 ID
    :return: {"documentType": str, "nodes": List[dict]}
    """
    file_path = Path(file_path)
    doc_type = detect_document_type(file_path)#根据后缀名判断文件类型，为后续处理器路由提供依据
    logger.info("路由判定文档类型: %s (file=%s)", doc_type, file_path.name)

    try:
        if doc_type == "markdown":
            nodes = _get_markdown_parser().parse(file_path, document_id, user_id)
        elif doc_type == "html":
            nodes = _get_html_parser().parse(file_path, document_id, user_id)
        elif doc_type == "code":
            nodes = _get_code_parser().parse(file_path, document_id, user_id)
        elif doc_type == "linebased":
            nodes = _get_linebased_parser().parse(file_path, document_id, user_id)
        elif doc_type == "pdf":
            nodes = _get_pdf_parser().parse(file_path, document_id, user_id)
        elif doc_type == "docx":
            nodes = _get_docx_parser().parse(file_path, document_id, user_id)
        else:
            # xlsx 暂未实现独立 parser，保留占位（warning + 空列表）
            logger.warning("xlsx 暂不支持解析，返回空 Node 列表: %s", file_path)
            nodes = []
    except Exception as e:
        logger.exception("解析器 %s 处理失败: %s", doc_type, file_path.name)
        raise

    logger.info(
        "解析完成: file=%s, type=%s, nodes=%d", file_path.name, doc_type, len(nodes)
    )
    return {"documentType": doc_type, "nodes": nodes}
