package com.resume.screening.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.resume.screening.domain.HiringDecision;
import com.resume.screening.dto.HiredCandidate;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface HiringDecisionMapper extends BaseMapper<HiringDecision> {

    /**
     * 该岗位录取过的人 + 当时的分。这两个东西合起来才是"门槛"：
     * 光知道谁被录用没用，得知道系统当时给了多少分，才能定位尺度。
     */
    @Select("""
            SELECT hd.resume_id AS resumeId,
                   ja.semantic_score AS semanticScore
              FROM hiring_decision hd
              JOIN job_application ja ON ja.id = hd.application_id
             WHERE hd.job_id = #{jobId}
               AND hd.outcome = 'HIRED'
               AND ja.semantic_score IS NOT NULL
             ORDER BY ja.semantic_score DESC
            """)
    List<HiredCandidate> selectHiredWithScore(@Param("jobId") Long jobId);
}
