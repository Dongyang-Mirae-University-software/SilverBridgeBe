package kr.silverbridge.main.domain.user.event;

public record UserWithdrawnEvent(
        String userId,
        String ipAddress,
        String userAgent
) {}
