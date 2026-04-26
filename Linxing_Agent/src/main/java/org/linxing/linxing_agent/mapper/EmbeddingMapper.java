package org.linxing.linxing_agent.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.linxing.linxing_agent.entity.FullEmbeddingRecord;
import org.linxing.linxing_agent.entity.VectorSearchResult;

@Mapper
public interface EmbeddingMapper {

    int batchInsertEmbeddings(@Param("list") List<FullEmbeddingRecord> list);

    List<VectorSearchResult> vectorSearch(
            @Param("userId") Integer userId,
            @Param("queryVector") String queryVector,
            @Param("limit") int limit
    );

    int deleteByChunkIds(@Param("chunkIds") List<Integer> chunkIds);

    int deleteByUserId(@Param("userId") Integer userId);
}
