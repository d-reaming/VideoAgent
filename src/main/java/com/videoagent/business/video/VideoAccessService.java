package com.videoagent.business.video;

import com.videoagent.access.auth.UserContext;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.storage.entity.Video;
import com.videoagent.storage.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 多租户访问控制：凡操作/查询某个视频，先经此处校验「属主」与「未软删」，未通过抛 403/404。
 */
@Service
@RequiredArgsConstructor
public class VideoAccessService {

    private final VideoMapper videoMapper;

    /** 当前登录用户（未登录的受保护请求不可能到达，这里兜底） */
    public Long requireUid() {
        Long uid = UserContext.get();
        if (uid == null) {
            throw new BusinessException(401, "未登录");
        }
        return uid;
    }

    /** 校验视频属主并返回该视频；不存在/非属主/已软删均拒绝 */
    public Video owned(Long videoId) {
        Video video = videoMapper.selectById(videoId);
        if (video == null || video.getDeletedAt() != null) {
            throw new BusinessException(404, "视频不存在: " + videoId);
        }
        Long uid = requireUid();
        // 严格属主校验：user_id 为 null/0（存量数据）一律不可被普通登录用户访问，杜绝"无属主即人人可读写"
        if (video.getUserId() == null || !video.getUserId().equals(uid)) {
            throw new BusinessException(403, "无权访问该资源");
        }
        return video;
    }
}