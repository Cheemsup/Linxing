package org.linxing.linxing_agent.rag.service;

import org.linxing.linxing_agent.rag.dto.DuplicateCheckResponse;
import org.linxing.linxing_agent.rag.dto.IngestResponse;
import org.springframework.web.multipart.MultipartFile;

public interface IIngestService {

    IngestResponse ingestFile(MultipartFile file, Integer userId, Boolean overwrite);

    //上传前同名文件预检：返回当前 user_id 下是否已存在同名文件及原文档 ID，供前端弹出覆盖确认框
    DuplicateCheckResponse checkDuplicate(Integer userId, String fileName);
}
