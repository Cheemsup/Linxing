package org.linxing.linxing_agent.rag.service;

import org.linxing.linxing_agent.rag.dto.IngestResponse;
import org.springframework.web.multipart.MultipartFile;

public interface IIngestService {

    IngestResponse ingestFile(MultipartFile file, Integer userId);
}
