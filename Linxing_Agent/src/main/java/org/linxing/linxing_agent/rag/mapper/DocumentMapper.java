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

    //按 user_id + file_name 查询，用于上传重名判重
    Optional<DocRecord> findByUserIdAndFileName(
            @Param("userId") Integer userId,
            @Param("fileName") String fileName
    );

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

    /** 更新文档物理路径（入库插入后再落盘 source 文件，回填 filePath）。 */
    int updateFilePath(@Param("id") Integer id, @Param("filePath") String filePath);

    int deleteById(@Param("id") Integer id);

    int deleteByUserId(@Param("userId") Integer userId);
}
