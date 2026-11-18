package com.example.bank.media.persistence;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 把业务主体（actorId/peerId）解析成 media_user.user_id：优先按 peerId，其次按业务主体，
 * 都找不到时退回 actorId（成员关系仍可追溯，不伪造 media 用户）。
 */
@Component
public class MediaIdentityResolver {

    private final MediaUserRepository users;

    public MediaIdentityResolver(MediaUserRepository users) {
        this.users = users;
    }

    public String resolve(String actorId, String peerId) {
        if (peerId != null && !peerId.trim().isEmpty()) {
            Map<String, Object> byPeer = users.findByPeerId(peerId.trim());
            if (byPeer != null) return String.valueOf(byPeer.get("user_id"));
        }
        if (actorId != null && !actorId.trim().isEmpty()) {
            Map<String, Object> bySubject = users.findActiveBySubject(actorId.trim());
            if (bySubject != null) return String.valueOf(bySubject.get("user_id"));
            return actorId.trim();
        }
        return null;
    }
}
