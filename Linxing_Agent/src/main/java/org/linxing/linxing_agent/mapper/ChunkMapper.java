package org.linxing.linxing_agent.mapper;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.linxing.linxing_agent.entity.Chunk;

@Mapper
public interface ChunkMapper {

    int insert(Chunk chunk);

    Optional<Chunk> findById(@Param("id") Integer id);

    List<Chunk> findByDocumentId(@Param("documentId") Integer documentId);

    List<Chunk> findByUserId(@Param("userId") Integer userId);

    List<Chunk> findByIds(@Param("ids") List<Integer> ids);

    int deleteById(@Param("id") Integer id);

    int deleteByDocumentId(@Param("documentId") Integer documentId);

    int deleteByUserId(@Param("userId") Integer userId);
}
