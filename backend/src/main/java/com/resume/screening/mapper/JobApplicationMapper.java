package com.resume.screening.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.resume.screening.domain.JobApplication;
import com.resume.screening.dto.BoardRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

public interface JobApplicationMapper extends BaseMapper<JobApplication> {

    /**
     * 候选人看板：先按语义分分组（高度匹配 → 部分相关 → 方向不符），组内按综合分倒序，未打分的排最后。
     *
     * <p>为什么不是直接按综合分排：跨方向的候选人语义分会集体挤在低分段（实测 38/38/42/42），
     * 而综合分里 0.4 的语义权重会把他们的分数抬到 60 出头，看起来"还行"，
     * 实际方向完全不符。语义分是筛选器，规则分才是排序依据。
     *
     * <p>阈值从 AppProperties 传入，不在 SQL 里硬编码。
     */
    @Select("""
            <script>
            SELECT ja.id, ja.job_id, ja.resume_id, ja.rule_score, ja.semantic_score, ja.score,
                   ja.hit_skills, ja.miss_skills, ja.reason, ja.status, ja.error_msg,
                   ja.created_at,
                   r.candidate_name, r.phone, r.email,
                   JSON_UNQUOTE(JSON_EXTRACT(r.parsed_json, '$.education.degree')) AS degree,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(r.parsed_json, '$.total_years')) AS DECIMAL(5,1)) AS total_years
            FROM job_application ja
            JOIN resume r ON r.id = ja.resume_id
            WHERE ja.job_id = #{jobId}
            <if test="minScore != null">
                AND ja.score &gt;= #{minScore}
            </if>
            <if test="degree != null and degree != ''">
                AND JSON_UNQUOTE(JSON_EXTRACT(r.parsed_json, '$.education.degree')) = #{degree}
            </if>
            ORDER BY (ja.score IS NULL),
                     CASE WHEN ja.semantic_score &gt;= #{highThreshold} THEN 0
                          WHEN ja.semantic_score &gt;= #{partialThreshold} THEN 1
                          WHEN ja.semantic_score IS NULL THEN 3
                          ELSE 2 END,
                     ja.score DESC, ja.id DESC
            </script>
            """)
    IPage<BoardRow> selectBoard(IPage<BoardRow> page,
                                @Param("jobId") Long jobId,
                                @Param("minScore") BigDecimal minScore,
                                @Param("degree") String degree,
                                @Param("highThreshold") int highThreshold,
                                @Param("partialThreshold") int partialThreshold);

    /** 岗位列表用：一次 GROUP BY 拿到所有岗位的投递数与均分 */
    @Select("""
            SELECT job_id AS jobId, COUNT(*) AS applicationCount, AVG(score) AS avgScore
              FROM job_application
             GROUP BY job_id
            """)
    List<com.resume.screening.dto.JobStat> selectJobStats();

    /** 岗位详情用：投递人数 */
    @Select("SELECT COUNT(*) FROM job_application WHERE job_id = #{jobId}")
    long countByJobId(@Param("jobId") Long jobId);

    /** 岗位详情用：平均分（只算已打分的） */
    @Select("SELECT AVG(score) FROM job_application WHERE job_id = #{jobId} AND score IS NOT NULL")
    BigDecimal avgScoreByJobId(@Param("jobId") Long jobId);

    @Update("UPDATE job_application SET status = #{status}, error_msg = #{errorMsg} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("errorMsg") String errorMsg);

    /** 重复投递即更新：清掉上一轮的分数，等消费者重算（需求 FR-MAT-05） */
    @Update("""
            UPDATE job_application
               SET status = 'SCORING',
                   rule_score = NULL, semantic_score = NULL, score = NULL,
                   hit_skills = NULL, miss_skills = NULL, reason = NULL, error_msg = NULL
             WHERE id = #{id}
            """)
    int resetForScoring(@Param("id") Long id);

    @Update("""
            UPDATE job_application
               SET rule_score = #{ruleScore},
                   semantic_score = #{semanticScore},
                   score = #{score},
                   hit_skills = #{hitSkills},
                   miss_skills = #{missSkills},
                   reason = #{reason},
                   status = 'SCORED',
                   error_msg = NULL
             WHERE id = #{id}
            """)
    int markScored(@Param("id") Long id,
                   @Param("ruleScore") BigDecimal ruleScore,
                   @Param("semanticScore") BigDecimal semanticScore,
                   @Param("score") BigDecimal score,
                   @Param("hitSkills") String hitSkills,
                   @Param("missSkills") String missSkills,
                   @Param("reason") String reason);

    /** 对账任务用：卡在 SCORING 的投递记录 */
    @Select("""
            SELECT * FROM job_application
             WHERE status = 'SCORING' AND updated_at < DATE_SUB(NOW(), INTERVAL #{staleSeconds} SECOND)
             ORDER BY id ASC
             LIMIT #{limit}
            """)
    List<JobApplication> selectStaleScoring(@Param("staleSeconds") int staleSeconds,
                                            @Param("limit") int limit);

    /** 排行榜重建用：某岗位所有已打分的投递 */
    @Select("SELECT resume_id, score FROM job_application WHERE job_id = #{jobId} AND score IS NOT NULL")
    List<JobApplication> selectScoredByJob(@Param("jobId") Long jobId);

    /** TOP N 快速通道：按 resumeId 批量取展示信息 */
    @Select("""
            <script>
            SELECT ja.resume_id, ja.score, r.candidate_name,
                   JSON_UNQUOTE(JSON_EXTRACT(r.parsed_json, '$.education.degree')) AS degree,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(r.parsed_json, '$.total_years')) AS DECIMAL(5,1)) AS total_years
            FROM job_application ja
            JOIN resume r ON r.id = ja.resume_id
            WHERE ja.job_id = #{jobId} AND ja.resume_id IN
            <foreach collection="resumeIds" item="rid" open="(" separator="," close=")">#{rid}</foreach>
            </script>
            """)
    List<BoardRow> selectTopRows(@Param("jobId") Long jobId, @Param("resumeIds") List<Long> resumeIds);

    // ------------------------------------------------- 官网投递：等解析 → 触发打分

    /** 某份简历投递过、且还在等解析的记录 */
    @Select("SELECT * FROM job_application WHERE resume_id = #{resumeId} AND status = 'WAITING'")
    List<JobApplication> selectWaitingByResumeId(@Param("resumeId") Long resumeId);

    /**
     * 把 WAITING 抢占成 SCORING。带 status 条件是为了幂等：
     * 两个消费者同时处理同一份简历时，只有一条能抢到，避免重复投递打分消息。
     */
    @Update("UPDATE job_application SET status = 'SCORING', error_msg = NULL WHERE id = #{id} AND status = 'WAITING'")
    int claimForScoring(@Param("id") Long id);

    /** 简历解析彻底失败时，把等着的投递一并判失败，别让 HR 看到永远转圈的记录 */
    @Update("""
            UPDATE job_application
               SET status = 'FAILED', error_msg = #{errorMsg}
             WHERE resume_id = #{resumeId} AND status = 'WAITING'
            """)
    int failWaitingByResumeId(@Param("resumeId") Long resumeId, @Param("errorMsg") String errorMsg);

    /** 官网投递去重用：这份简历是否已经投过这个岗位 */
    @Select("SELECT COUNT(*) FROM job_application WHERE job_id = #{jobId} AND resume_id = #{resumeId}")
    int countByJobAndResume(@Param("jobId") Long jobId, @Param("resumeId") Long resumeId);

    /**
     * 对账用：简历早就解析完了，投递记录却还停在 WAITING。
     * 正常情况下 ExtractConsumer 会接力推进，这里只兜"接力的那一刻崩了"。
     */
    @Select("""
            SELECT ja.* FROM job_application ja
              JOIN resume r ON r.id = ja.resume_id
             WHERE ja.status = 'WAITING' AND r.status = 'EXTRACTED'
             ORDER BY ja.id ASC
             LIMIT #{limit}
            """)
    List<JobApplication> selectWaitingWithParsedResume(@Param("limit") int limit);
}
