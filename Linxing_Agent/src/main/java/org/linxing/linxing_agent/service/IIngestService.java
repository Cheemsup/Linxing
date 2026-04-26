package org.linxing.linxing_agent.service;

import org.linxing.linxing_agent.dto.IngestResponse;
import org.springframework.web.multipart.MultipartFile;

public interface IIngestService {

    IngestResponse ingestFile(MultipartFile file, Integer userId);
}
