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
public class ChunkTreeVO {

    private Integer chunkId;

    private String titlePath;

    private Short chunkLevel;

    private String chunkType;

    private String textPreview;

    private Integer siblingIndex;

    private List<ChunkTreeVO> children;
}
