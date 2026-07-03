"""
Document Analysis Service - Python 文档解析服务

基于 Docling 实现 PDF/DOCX 文档解析，输出有序、原子化的 Node JSON 序列。
Java 侧通过 HTTP 调用此服务，获取 Node 列表后完成后续语义增强、Chunk 构建等工作。
"""

__version__ = "1.0.0"
