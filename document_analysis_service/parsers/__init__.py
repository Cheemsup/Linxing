"""
parsers 子包：按文档类型组织的解析器集合。

所有解析器对外签名一致：
    parse(file_path: str, document_id: int, user_id: int) -> List[Node dict]
返回的 Node dict 协议与 Java 侧 NodeDTO 对应（id/type/text/imagePath/html/language/
level/page/bbox/hash/rowCount/colCount/titlePath/groupId）。

层级平等，无父子关系：
- LineBasedParser: log / csv / tsv / txt（标准库 re，零新增依赖）
- CodeParser: 源代码文件 java/py/js/ts/...（标准库 re）
- MarkdownParser: Markdown 文件（mistune 3 结构识别 + 手写领域逻辑）
- HtmlParser: HTML 文件（beautifulsoup4 DOM 遍历 + 手写领域逻辑）
- PdfParser: PDF 文件（PyMuPDF + pdfplumber，需图片存储目录）
- DocxParser: DOCX 文件（python-docx，需图片存储目录）

路由（router.py）为唯一派发入口：detect_document_type / parse 见 router.py。
所有类型平等，pdf/docx 单例由 router 从 config 读取图片目录后懒加载注入；
md/html/code/linebased 无图片需求，router 内单例直接派发。

导入策略：
- 本 __init__ 只 eager import 无重型依赖的文本类解析器（linebased/code/markdown/html），
  保持 `import parsers` 的依赖面与改造前一致；
- PdfParser / DocxParser 涉及 fitz/pdfplumber/python-docx 等重型依赖，改为懒加载：
  由 router.py 在首次需要时 `from .pdf_parser import PdfParser`，
  避免在仅做类型判定或纯文本解析时也强制加载这些库。
"""

from .linebased_parser import LineBasedParser, CHUNK_THRESHOLD
from .code_parser import CodeParser, CHUNK_THRESHOLD_CODE
from .markdown_parser import MarkdownParser, CHUNK_THRESHOLD as MD_CHUNK_THRESHOLD
from .html_parser import HtmlParser, CHUNK_THRESHOLD as HTML_CHUNK_THRESHOLD

__all__ = [
    "LineBasedParser",
    "CHUNK_THRESHOLD",
    "CodeParser",
    "CHUNK_THRESHOLD_CODE",
    "MarkdownParser",
    "MD_CHUNK_THRESHOLD",
    "HtmlParser",
    "HTML_CHUNK_THRESHOLD",
    # PdfParser / DocxParser 懒加载，不在此 eager import；
    # 使用：from parsers.pdf_parser import PdfParser / from parsers.docx_parser import DocxParser
    # （router.py 已封装懒加载 + 图片目录注入，外部一般无需直接 import）
    "PdfParser",
    "DocxParser",
]


def __getattr__(name):
    """PEP 562：按需懒加载重型依赖解析器，避免 import parsers 时强制加载 fitz/docx。"""
    if name == "PdfParser":
        from .pdf_parser import PdfParser
        return PdfParser
    if name == "DocxParser":
        from .docx_parser import DocxParser
        return DocxParser
    raise AttributeError(f"module 'parsers' has no attribute {name!r}")
