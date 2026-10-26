package com.resume.screening.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.resume.screening.cache.ResumeStatusCache;
import com.resume.screening.cache.UploadRateLimiter;
import com.resume.screening.common.BizException;
import com.resume.screening.common.ErrorCode;
import com.resume.screening.common.Json;
import com.resume.screening.config.AppProperties;
import com.resume.screening.domain.Resume;
import com.resume.screening.domain.ResumeStatus;
import com.resume.screening.dto.ResumeJobTag;
import com.resume.screening.dto.Views;
import com.resume.screening.mapper.ResumeMapper;
import com.resume.screening.mq.ExtractMessage;
import com.resume.screening.mq.MqProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final ResumeMapper resumeMapper;
    private final StorageService storage;
    private final ResumeStatusCache statusCache;
    private final UploadRateLimiter rateLimiter;
    private final MqProducer producer;
    private final AppProperties props;
    private final Json json;
    private final TransactionTemplate txTemplate;

    // ------------------------------------------------------------------ 上传

    /** 落库结果：duplicated=true 表示命中去重，resume 是库里已有那条 */
    public record Stored(Resume resume, boolean duplicated) {
    }

    /** 单文件上传（HR 后台：线下简历入库）。事务边界只包住"落库 + 登记 afterCommit 投递"。 */
    public Views.UploadResult upload(MultipartFile file, String userId) {
        rateLimiter.check(userId);
        Stored stored = storeAndEnqueue(file);
        return new Views.UploadResult(stored.resume().getId(), stored.resume().getStatus(),
                stored.duplicated());
    }

    /** 批量上传：一次请求只计一次限流，单份失败不影响其他（需求 FR-RES-06） */
    public List<Views.BatchItem> batchUpload(List<MultipartFile> files, String userId) {
        if (files == null || files.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请至少选择 1 份简历");
        }
        if (files.size() > 20) {
            throw new BizException(ErrorCode.BAD_REQUEST, "单次最多上传 20 份简历");
        }
        rateLimiter.check(userId);

        List<Views.BatchItem> results = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            String name = file.getOriginalFilename();
            try {
                Stored s = storeAndEnqueue(file);
                results.add(new Views.BatchItem(name, s.resume().getId(), s.resume().getStatus(),
                        s.duplicated(), null));
            } catch (BizException e) {
                results.add(new Views.BatchItem(name, null, null, false, e.getMessage()));
            } catch (Exception e) {
                log.error("批量上传单项失败 file={}", name, e);
                results.add(new Views.BatchItem(name, null, null, false, "上传失败：" + e.getMessage()));
            }
        }
        return results;
    }

    /**
     * 落库 + 投递解析消息。官网投递（PublicApplyService）也走这里，
     * 保证两条入口的文件存储、哈希去重、解析投递行为完全一致。
     */
    public Stored storeAndEnqueue(MultipartFile file) {
        validate(file);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.BAD_FILE, "读取上传文件失败");
        }
        String hash = sha256(bytes);

        Resume existing = resumeMapper.selectOne(
                new LambdaQueryWrapper<Resume>().eq(Resume::getFileHash, hash));
        if (existing != null) {
            log.info("命中去重 hash={} resumeId={} status={}", hash, existing.getId(), existing.getStatus());
            refreshCache(existing);
            return new Stored(existing, true);
        }

        String relativePath = storage.save(bytes, file.getOriginalFilename());

        Resume resume = new Resume();
        resume.setFileName(safeName(file.getOriginalFilename()));
        resume.setFilePath(relativePath);
        resume.setFileHash(hash);
        resume.setStatus(ResumeStatus.PENDING.name());

        try {
            txTemplate.executeWithoutResult(status -> {
                resumeMapper.insert(resume);
                // 事务提交后才投递，避免"消息发了但库里回滚了"
                producer.sendAfterCommit(props.getMq().getExtractTopic(), String.valueOf(resume.getId()),
                        new ExtractMessage(UUID.randomUUID().toString(), resume.getId(), relativePath, 1,
                                System.currentTimeMillis()));
            });
        } catch (DuplicateKeyException e) {
            // 并发上传同一份文件，唯一索引兜住：返回已有记录
            Resume dup = resumeMapper.selectOne(
                    new LambdaQueryWrapper<Resume>().eq(Resume::getFileHash, hash));
            if (dup == null) {
                throw new BizException(ErrorCode.INTERNAL, "并发上传冲突，请重试");
            }
            return new Stored(dup, true);
        }

        log.info("简历已入库 resumeId={} hash={} path={} size={}B",
                resume.getId(), hash, relativePath, bytes.length);
        statusCache.put(resume.getId(), ResumeStatus.PENDING.name(), null, null);
        return new Stored(resume, false);
    }

    // ------------------------------------------------------------------ 查询

    public Views.ResumeStatusView status(long id) {
        var cached = statusCache.get(id);
        if (cached.isPresent()) {
            return new Views.ResumeStatusView(id, cached.get().status(), cached.get().errorMsg(),
                    cached.get().candidateName());
        }
        Resume r = require(id);
        refreshCache(r);
        return new Views.ResumeStatusView(id, r.getStatus(), r.getErrorMsg(), r.getCandidateName());
    }

    public Views.ResumeDetailView detail(long id) {
        Resume r = require(id);
        return new Views.ResumeDetailView(r.getId(), r.getCandidateName(), r.getPhone(), r.getEmail(),
                r.getFileName(), r.getStatus(), r.getErrorMsg(), r.getCostMs(), r.getCreatedAt(),
                json.read(r.getParsedJson()));
    }

    public Views.PageView<Views.ResumeListItem> list(long page, long size, String status) {
        LambdaQueryWrapper<Resume> qw = new LambdaQueryWrapper<Resume>()
                .eq(status != null && !status.isBlank(), Resume::getStatus, status)
                .orderByDesc(Resume::getCreatedAt)
                .orderByDesc(Resume::getId);
        Page<Resume> p = resumeMapper.selectPage(new Page<>(page, Math.min(size, 100)), qw);
        if (p.getRecords().isEmpty()) {
            return new Views.PageView<>(p.getTotal(), p.getCurrent(), p.getSize(), List.of());
        }

        // 一次 GROUP BY 拿到本页简历投过的岗位，避免逐行查询
        List<Long> ids = p.getRecords().stream().map(Resume::getId).toList();
        Map<Long, String> jobTags = resumeMapper.selectJobTitles(ids).stream()
                .collect(Collectors.toMap(ResumeJobTag::getResumeId, ResumeJobTag::getTitles, (a, b) -> a));

        List<Views.ResumeListItem> items = p.getRecords().stream()
                .map(r -> new Views.ResumeListItem(r.getId(), r.getCandidateName(), r.getPhone(), r.getEmail(),
                        r.getFileName(), r.getStatus(), r.getErrorMsg(), r.getCostMs(), r.getCreatedAt(),
                        jobTags.getOrDefault(r.getId(), "")))
                .toList();
        return new Views.PageView<>(p.getTotal(), p.getCurrent(), p.getSize(), items);
    }

    // ------------------------------------------------------------------ 重试

    public Views.UploadResult retry(long id) {
        Resume r = require(id);
        if (ResumeStatus.EXTRACTED.name().equals(r.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该简历已解析完成，无需重新解析");
        }

        resumeMapper.updateStatus(id, ResumeStatus.PENDING.name(), null);
        statusCache.put(id, ResumeStatus.PENDING.name(), null, r.getCandidateName());

        producer.sendAfterCommit(props.getMq().getExtractTopic(), String.valueOf(id),
                new ExtractMessage(UUID.randomUUID().toString(), id, r.getFilePath(), 1,
                        System.currentTimeMillis()));

        log.info("简历重新解析已投递 resumeId={} 原状态={}", id, r.getStatus());
        return new Views.UploadResult(id, ResumeStatus.PENDING.name(), false);
    }

    // ---------------------------------------------------------------- internal

    private Resume require(long id) {
        Resume r = resumeMapper.selectById(id);
        if (r == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "简历不存在：" + id);
        }
        return r;
    }

    private void refreshCache(Resume r) {
        statusCache.put(r.getId(), r.getStatus(), r.getErrorMsg(), r.getCandidateName());
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_FILE, "上传文件为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException(ErrorCode.BAD_FILE, "文件超过 10MB 限制");
        }
        String name = file.getOriginalFilename();
        String contentType = file.getContentType();
        boolean pdfName = name != null && name.toLowerCase().endsWith(".pdf");
        boolean pdfType = contentType != null && contentType.toLowerCase().contains("pdf");
        if (!pdfName && !pdfType) {
            throw new BizException(ErrorCode.BAD_FILE, "只接受 PDF 文件");
        }
        // 魔数校验：扩展名可以骗人，文件头不会
        byte[] head;
        try {
            head = file.getInputStream().readNBytes(4);
        } catch (IOException e) {
            throw new BizException(ErrorCode.BAD_FILE, "读取上传文件失败");
        }
        if (head.length < 4 || head[0] != '%' || head[1] != 'P' || head[2] != 'D' || head[3] != 'F') {
            throw new BizException(ErrorCode.BAD_FILE, "文件内容不是有效的 PDF");
        }
    }

    private static String safeName(String original) {
        if (original == null || original.isBlank()) {
            return "resume.pdf";
        }
        String name = original.replace("\\", "/");
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算文件哈希失败", e);
        }
    }
}
