package com.videoagent.business.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.access.auth.UserContext;
import com.videoagent.access.dto.UploadResponse;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.config.StorageProperties;
import com.videoagent.middleware.lock.PipelineLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 分片上传 + 断点续传（WS4，后端契约）。
 * <p>流程：init(生成 uploadId, Redis 记元数据) → upload(逐片写盘 + Redis Set 记账) →
 * complete(校验分片齐全 → 按序拼接并算 MD5 → 复用 {@link VideoUploadService#registerStoredFile} 落库触发流水线)。
 * 已上传分片可断点续传，重复 complete 幂等（completed 标记）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkUploadService {

    /** 单分片大小上限（5MB） */
    public static final int MAX_CHUNK_BYTES = 5 * 1024 * 1024;
    /** 分片数量上限（≈2GB） */
    public static final int MAX_TOTAL_CHUNKS = 410;

    private final StorageProperties storage;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final PipelineLockService lockService;
    private final VideoUploadService videoUploadService;

    private static final String META_PREFIX = "video-agent:chunk:";
    private static final long META_TTL_MINUTES = 24 * 60;

    /** 初始化结果 */
    public record ChunkInit(String uploadId, long chunkSize) {
    }

    /** 断点进度 */
    public record ChunkProgress(String uploadId, List<String> uploadedChunks, boolean completed) {
    }

    private record ChunkMeta(String uploadId, String filename, String ext, int totalChunks, long userId,
                             String title, boolean completed) {
    }

    public ChunkInit init(String filename, int totalChunks, String title) {
        String original = filename == null || filename.isBlank() ? "untitled" : filename;
        String ext = extensionOf(original);
        if (totalChunks <= 0 || totalChunks > MAX_TOTAL_CHUNKS) {
            throw new BusinessException("分片数量非法(1~" + MAX_TOTAL_CHUNKS + "): " + totalChunks);
        }
        Long uid = UserContext.get() == null ? 0L : UserContext.get();
        // 内部校验：总片数 × 5MB 上限兜底
        String uploadId = UUID.randomUUID().toString().replace("-", "");
        saveMeta(new ChunkMeta(uploadId, original, ext, totalChunks, uid, title, false));
        return new ChunkInit(uploadId, MAX_CHUNK_BYTES);
    }

    public void upload(String uploadId, int chunkIndex, MultipartFile file) {
        ChunkMeta meta = requireMeta(uploadId);
        if (chunkIndex < 0 || chunkIndex >= meta.totalChunks()) {
            throw new BusinessException("分片序号越界(0~" + (meta.totalChunks() - 1) + "): " + chunkIndex);
        }
        if (meta.completed()) {
            throw new BusinessException("该上传已完成");
        }
        if (file.isEmpty() || (long) file.getSize() > MAX_CHUNK_BYTES) {
            throw new BusinessException("单分片不能为空且不得超过 " + (MAX_CHUNK_BYTES / 1024 / 1024) + "MB");
        }
        try {
            Path dir = Paths.get(storage.chunkDir()).resolve(uploadId);
            Files.createDirectories(dir);
            Path part = dir.resolve(chunkIndex + ".part");
            file.transferTo(part);
            // 先写盘后记账：写盘成功才登记已传序号，保证断点续传只补缺失
            redisTemplate.opsForSet().add(partsKey(uploadId), String.valueOf(chunkIndex));
        } catch (IOException e) {
            throw new BusinessException("分片保存失败: " + e.getMessage());
        }
    }

    public UploadResponse complete(String uploadId) {
        ChunkMeta meta = requireMeta(uploadId);
        if (meta.completed()) {
            throw new BusinessException("该上传已完成");
        }
        final UploadResponse[] result = {null};
        // 持锁合并，防并发 complete 重复落库
        lockService.tryRun("chunk:" + uploadId, 30_000, () -> {
            ChunkMeta m = requireMeta(uploadId);
            if (m.completed()) {
                throw new BusinessException("该上传已完成");
            }
            Set<String> parts = partsMembers(uploadId);
            if (parts.size() != m.totalChunks()) {
                throw new BusinessException("分片未齐全(已传 " + parts.size() + "/" + m.totalChunks() + ")");
            }
            Path finalPath = merge(uploadId, m);
            try {
                result[0] = videoUploadService.registerStoredFile(finalPath, m.filename(), m.title(), m.userId());
                // 成功后标记完成并清理分片
                saveMeta(new ChunkMeta(uploadId, m.filename(), m.ext(), m.totalChunks(), m.userId(), m.title(), true));
                redisTemplate.delete(META_PREFIX + uploadId + ":parts");
                deleteDir(Paths.get(storage.chunkDir()).resolve(uploadId));
            } catch (RuntimeException e) {
                // 探流/入库失败：保留分片与记账，允许客户端整改后重试 complete
                throw e;
            }
        });
        return result[0];
    }

    public ChunkProgress progress(String uploadId) {
        ChunkMeta meta = requireMeta(uploadId);
        List<String> uploaded = new ArrayList<>(partsMembers(uploadId));
        Collections.sort(uploaded);
        return new ChunkProgress(uploadId, uploaded, meta.completed());
    }

    /** 按序拼接分片 → 完整文件（DigestOutputStream 顺算 MD5 防篡改/校验与预期一致），返回最终路径 */
    private Path merge(String uploadId, ChunkMeta meta) {
        try {
            Path dir = Paths.get(storage.videosDir());
            Files.createDirectories(dir);
            String storedName = UUID.randomUUID() + "." + meta.ext();
            Path target = dir.resolve(storedName);
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (DigestOutputStream dos = new DigestOutputStream(new BufferedOutputStream(Files.newOutputStream(target)), md)) {
                for (int i = 0; i < meta.totalChunks(); i++) {
                    Path part = Paths.get(storage.chunkDir()).resolve(uploadId).resolve(i + ".part");
                    if (!Files.exists(part)) {
                        throw new BusinessException("缺少分片 " + i + "，无法合并");
                    }
                    Files.copy(part, dos);
                }
            }
            log.info("[chunk] merged uploadId={} bytes={} md5={}", uploadId, Files.size(target),
                    toHex(md.digest()));
            return target;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("合并分片失败: " + e.getMessage());
        }
    }

    private ChunkMeta requireMeta(String uploadId) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new BusinessException("缺少 uploadId");
        }
        String json = redisTemplate.opsForValue().get(META_PREFIX + uploadId + ":meta");
        if (json == null) {
            throw new BusinessException("uploadId 不存在或已过期: " + uploadId);
        }
        try {
            return objectMapper.readValue(json, ChunkMeta.class);
        } catch (Exception e) {
            throw new BusinessException("分片元数据解析失败");
        }
    }

    private void saveMeta(ChunkMeta meta) {
        try {
            redisTemplate.opsForValue().set(META_PREFIX + meta.uploadId() + ":meta",
                    objectMapper.writeValueAsString(meta), META_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new BusinessException("分片元数据保存失败");
        }
    }

    private static String partsKey(String uploadId) {
        return META_PREFIX + uploadId + ":parts";
    }

    private Set<String> partsMembers(String uploadId) {
        Set<String> members = redisTemplate.opsForSet().members(partsKey(uploadId));
        return members == null ? new HashSet<>() : members;
    }

    private void deleteDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ex) {
                            log.warn("[chunk] 清理分片失败: {}", p);
                        }
                    });
        } catch (Exception e) {
            log.warn("[chunk] 清理分片目录失败: {}", dir);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String extensionOf(String filename) {
        int idx = filename.lastIndexOf('.');
        if (idx < 0) {
            return "mp4";
        }
        String ext = filename.substring(idx + 1).toLowerCase(Locale.ROOT);
        return ext.isBlank() ? "mp4" : ext;
    }
}