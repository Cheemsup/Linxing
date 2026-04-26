package org.linxing.linxing_agent.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.linxing.linxing_agent.entity.ActivityLog;

@Mapper
public interface ActivityLogMapper {

    int insert(ActivityLog activityLog);

    List<ActivityLog> findByUserId(@Param("userId") Integer userId);

    long countByUserIdAndActionAndDate(
            @Param("userId") Integer userId,
            @Param("actionType") String actionType,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    long countTodayUploads(@Param("userId") Integer userId);

    long countTodayQueries(@Param("userId") Integer userId);

    int deleteById(@Param("id") Long id);

    int deleteByUserId(@Param("userId") Integer userId);
}
