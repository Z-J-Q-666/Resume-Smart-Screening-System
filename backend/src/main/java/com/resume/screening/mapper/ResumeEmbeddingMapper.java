package com.resume.screening.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.resume.screening.domain.ResumeEmbedding;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ResumeEmbeddingMapper extends BaseMapper<ResumeEmbedding> {

    /**
     * 批量取向量。model 一并过滤：换过嵌入模型之后旧向量维度不同，混用会算出错误的相似度。
     */
    @Select("""
            <script>
            SELECT * FROM resume_embedding
             WHERE model = #{model}
               AND resume_id IN
             <foreach collection="resumeIds" item="rid" open="(" separator="," close=")">#{rid}</foreach>
            </script>
            """)
    List<ResumeEmbedding> selectByResumeIds(@Param("resumeIds") List<Long> resumeIds,
                                            @Param("model") String model);
}
