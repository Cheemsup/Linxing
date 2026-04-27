package org.linxing.linxing_agent.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentPreviewVO {

    private Integer id;

    private String fileName;

    private String fileType;

    private String previewType;

    private String textContent;

    private List<String> pages;
}
