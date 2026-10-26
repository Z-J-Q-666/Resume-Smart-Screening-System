package com.resume.screening.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.resume.screening.domain.Resume;
import com.resume.screening.dto.ResumeJobTag;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ResumeMapper extends BaseMapper<Resume> {

    /** 状态流转：显式 SQL 才能把 error_msg 写回 NULL */
    @Update("UPDATE resume SET status = #{status}, error_msg = #{errorMsg} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("errorMsg") String errorMsg);

    @Update("""
            UPDATE resume
               SET status = 'EXTRACTED',
                   parsed_json = #{parsedJson},
                   raw_text = #{rawText},
                   cost_ms = #{costMs},
                   candidate_name = COALESCE(#{name}, candidate_name),
                   phone = COALESCE(#{phone}, phone),
                   email = COALESCE(#{email}, email),
                   error_msg = NULL
             WHERE id = #{id}
            """)
    int markExtracted(@Param("id") Long id,
                      @Param("parsedJson") String parsedJson,
                      @Param("rawText") String rawText,
                      @Param("costMs") Integer costMs,
                      @Param("name") String name,
                      @Param("phone") String phone,
                      @Param("email") String email);

    /**
     * 官网投递时求职者填的联系方式先落库（解析前 HR 也能看到人）。
     * 只补空位、不覆盖已有值：命中去重时这份文件可能已经属于别人，
     * 不能因为另一次投递把人名电话改掉。
     */
    @Update("""
            UPDATE resume
               SET candidate_name = COALESCE(candidate_name, #{name}),
                   phone = COALESCE(phone, #{phone}),
                   email = COALESCE(email, #{email})
             WHERE id = #{id}
            """)
    int fillContact(@Param("id") Long id,
                    @Param("name") String name,
                    @Param("phone") String phone,
                    @Param("email") String email);

    /** 简历列表用：每份简历投过哪些岗位 */
    @Select("""
            <script>
            SELECT ja.resume_id AS resumeId,
                   GROUP_CONCAT(DISTINCT j.title ORDER BY j.id SEPARATOR '、') AS titles
              FROM job_application ja
              JOIN job j ON j.id = ja.job_id
             WHERE ja.resume_id IN
             <foreach collection="resumeIds" item="rid" open="(" separator="," close=")">#{rid}</foreach>
             GROUP BY ja.resume_id
            </script>
            """)
    List<ResumeJobTag> selectJobTitles(@Param("resumeIds") List<Long> resumeIds);

    /** 对账任务用：漏投或宕机后遗留的简历 */
    @Select("""
            SELECT * FROM resume
             WHERE (status = 'PENDING' AND updated_at < DATE_SUB(NOW(), INTERVAL #{pendingSeconds} SECOND))
                OR (status = 'PARSING' AND updated_at < DATE_SUB(NOW(), INTERVAL #{parsingSeconds} SECOND))
             ORDER BY id ASC
             LIMIT #{limit}
            """)
    List<Resume> selectStale(@Param("pendingSeconds") int pendingSeconds,
                             @Param("parsingSeconds") int parsingSeconds,
                             @Param("limit") int limit);
}
