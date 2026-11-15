package com.resume.screening.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.resume.screening.domain.AiCallLog;
import com.resume.screening.dto.AiTaskStat;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface AiCallLogMapper extends BaseMapper<AiCallLog> {

    /**
     * 按 task 聚合。别名刻意写成下划线形式，交给 map-underscore-to-camel-case 转换，
     * 避免和 MyBatis 的列名解析规则打架。
     */
    @Select("""
            SELECT task,
                   COUNT(*)                                  AS calls,
                   COALESCE(SUM(cache_hit), 0)               AS cache_hits,
                   COALESCE(SUM(prompt_tokens), 0)           AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0)       AS completion_tokens,
                   COALESCE(ROUND(AVG(cost_ms), 0), 0)       AS avg_cost_ms
              FROM ai_call_log
             WHERE created_at >= #{since}
             GROUP BY task
            """)
    List<AiTaskStat> selectTaskStats(@Param("since") LocalDateTime since);
}
