package org.linxing.linxing_agent.rag.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.linxing.linxing_agent.rag.entity.FullEmbeddingRecord;
import org.linxing.linxing_agent.rag.entity.VectorSearchResult;

@Mapper
public interface EmbeddingMapper {

    /**
     * 批量写入向量记录。
     * @param dimension embedding 输出维度（rag.vector-store.dimension），用于 INSERT cast ::vector(${dimension})
     */
    int batchInsertEmbeddings(@Param("list") List<FullEmbeddingRecord> list, @Param("dimension") int dimension);

    List<VectorSearchResult> vectorSearch(
            @Param("userId") Integer userId,
            @Param("queryVector") String queryVector,
            @Param("limit") int limit
    );

    int deleteByChunkIds(@Param("chunkIds") List<Integer> chunkIds);

    int deleteByUserId(@Param("userId") Integer userId);
}
