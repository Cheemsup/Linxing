package org.linxing.linxing_agent.rag.mapper;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.linxing.linxing_agent.rag.entity.DocRecord;

@Mapper
public interface DocumentMapper {

    int insert(DocRecord docRecord);

    Optional<DocRecord> findById(@Param("id") Integer id);

    List<DocRecord> findByUserId(@Param("userId") Integer userId);

    List<DocRecord> findByUserIdAndStatus(
            @Param("userId") Integer userId,
            @Param("status") String status
    );

    List<DocRecord> findByUserIdPaged(
            @Param("userId") Integer userId,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    long countByUserId(@Param("userId") Integer userId);

    int updateStatus(@Param("id") Integer id, @Param("status") String status);

    int update(DocRecord docRecord);

    int deleteById(@Param("id") Integer id);

    int deleteByUserId(@Param("userId") Integer userId);
}
