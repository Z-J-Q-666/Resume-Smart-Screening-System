package com.resume.screening.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.resume.screening.cache.UploadRateLimiter;
import com.resume.screening.common.BizException;
import com.resume.screening.common.ErrorCode;
import com.resume.screening.config.AppProperties;
import com.resume.screening.domain.ApplicationStatus;
import com.resume.screening.domain.Job;
import com.resume.screening.domain.JobApplication;
import com.resume.screening.domain.Resume;
import com.resume.screening.domain.ResumeStatus;
import com.resume.screening.dto.Views;
import com.resume.screening.mapper.JobApplicationMapper;
import com.resume.screening.mapper.ResumeMapper;
import com.resume.screening.mq.MatchMessage;
import com.resume.screening.mq.MqProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * 求职者侧（官网投递入口）。
 *
 * 与 HR 侧的边界：
 *   - 这里只做"看岗位 + 交简历"，没有任何后台数据的读取口子；
 *   - 返回值不带主键，不暴露投递人数、分数；
 *   - 简历解析是异步的，所以投递记录先落 WAITING，解析成功后再由 ExtractConsumer 接力打分。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicApplyService {

    private static final int NAME_MAX = 50;
    private static final int PHONE_MAX = 20;
    private static final int EMAIL_MAX = 100;

    private final JobService jobService;
    private final ResumeService resumeService;
    private final ResumeMapper resumeMapper;
    private final JobApplicationMapper applicationMapper;
    private final UploadRateLimiter rateLimiter;
    private final MqProducer producer;
    private final AppProperties props;
    private final com.resume.screening.common.Json json;
    private final TransactionTemplate txTemplate;

    /** 官网岗位详情页用；已关闭的岗位也返回，让页面能给出"已关闭"提示而不是 404 */
    public Views.PublicJobView job(long jobId) {
        Job j = jobService.require(jobId);
        return new Views.PublicJobView(j.getId(), j.getTitle(), j.getDepartment(), j.getEducationReq(),
                j.getExpYearsReq(), json.toStringList(j.getMustSkills()), json.toStringList(j.getPlusSkills()),
                j.getJdText(), j.getStatus() != null && j.getStatus() == 1);
    }

    public Views.PublicApplyResult apply(long jobId, String name, String phone, String email,
                                         MultipartFile file, String clientIp) {
        String safeName = clean(name, NAME_MAX);
        String safePhone = clean(phone, PHONE_MAX);
        String safeEmail = clean(email, EMAIL_MAX);

        if (safeName == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写姓名");
        }
        if (safePhone == null && safeEmail == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "手机号和邮箱至少填一个，方便 HR 联系你");
        }
        if (safeEmail != null && !safeEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "邮箱格式不正确");
        }
        if (safePhone != null && !safePhone.matches("^[0-9+\\-\\s()]{6,20}$")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "手机号格式不正确");
        }

        Job job = jobService.require(jobId);
        if (job.getStatus() == null || job.getStatus() != 1) {
            throw new BizException(ErrorCode.JOB_CLOSED, "该岗位已关闭投递，看看其他岗位吧");
        }

        rateLimiter.check("ip:" + clientIp);

        ResumeService.Stored stored = resumeService.storeAndEnqueue(file);
        Resume resume = stored.resume();

        // 命中去重时可能拿到一份此前解析失败的简历，直接把原因告诉求职者，别让他干等
        if (ResumeStatus.FAILED.name().equals(resume.getStatus())) {
            String reason = resume.getErrorMsg() == null ? "简历解析失败" : resume.getErrorMsg();
            throw new BizException(ErrorCode.NO_TEXT_LAYER,
                    "这份简历没能读取到有效文字（" + reason + "），请上传文本版 PDF 后重试");
        }

        resumeMapper.fillContact(resume.getId(), safeName, safePhone, safeEmail);

        if (applicationMapper.countByJobAndResume(jobId, resume.getId()) > 0) {
            log.info("重复投递拦截 jobId={} resumeId={}", jobId, resume.getId());
            return new Views.PublicApplyResult(true, true, "你已经投递过该岗位，无需重复提交");
        }

        boolean parsed = ResumeStatus.EXTRACTED.name().equals(resume.getStatus());
        Long applicationId = txTemplate.execute(status -> {
            JobApplication app = new JobApplication();
            app.setJobId(jobId);
            app.setResumeId(resume.getId());
            app.setStatus(parsed ? ApplicationStatus.SCORING.name() : ApplicationStatus.WAITING.name());
            applicationMapper.insert(app);

            if (parsed) {
                producer.sendAfterCommit(props.getMq().getMatchTopic(), String.valueOf(app.getId()),
                        new MatchMessage(UUID.randomUUID().toString(), app.getId(), resume.getId(), jobId,
                                System.currentTimeMillis()));
            }
            return app.getId();
        });

        log.info("官网投递已受理 jobId={} resumeId={} applicationId={} duplicated={} status={}",
                jobId, resume.getId(), applicationId, stored.duplicated(),
                parsed ? ApplicationStatus.SCORING : ApplicationStatus.WAITING);

        // 补偿竞态：投递记录插入与简历解析完成可能几乎同时发生，
        // 消费者那一轮的 WAITING 查询会漏掉这条刚插入的记录。这里再确认一次状态。
        if (!parsed) {
            promoteIfAlreadyParsed(applicationId, resume.getId());
        }

        return new Views.PublicApplyResult(true, false,
                parsed ? "投递成功，HR 会尽快查看你的简历" : "投递成功，简历正在解析，HR 会尽快查看");
    }

    private void promoteIfAlreadyParsed(Long applicationId, Long resumeId) {
        Resume latest = resumeMapper.selectById(resumeId);
        if (latest == null || !ResumeStatus.EXTRACTED.name().equals(latest.getStatus())) {
            return;
        }
        if (applicationMapper.claimForScoring(applicationId) != 1) {
            return;
        }
        try {
            producer.send(props.getMq().getMatchTopic(), String.valueOf(applicationId),
                    new MatchMessage(UUID.randomUUID().toString(), applicationId, resumeId,
                            applicationMapper.selectById(applicationId).getJobId(),
                            System.currentTimeMillis()));
            log.info("投递记录补触发打分 applicationId={}", applicationId);
        } catch (Exception e) {
            applicationMapper.updateStatus(applicationId, ApplicationStatus.WAITING.name(), null);
            log.error("补触发打分失败，留给对账任务 applicationId={} err={}", applicationId, e.getMessage());
        }
    }

    /** trim + 截断；空串返回 null */
    private static String clean(String v, int max) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
