package kr.silverbridge.main.domain.notification.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import lombok.*;

@Entity
@Table(name = "fcm_tokens", indexes = {
        @Index(name = "idx_fcm_tokens_user_id", columnList = "user_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class FcmToken extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 6)
    private String userId;

    @Column(nullable = false, length = 500, unique = true)
    private String token;

    // 플랫폼 (ANDROID, IOS, WEB)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String platform = "ANDROID";

    // 공유 디바이스에서 다른 사용자가 로그인하면 토큰 소유자를 현재 사용자로 갱신한다 (M-S2-2).
    // 토큰은 디바이스 단위라, 이전 사용자 소유로 남으면 그 사람의 알림이 현 사용자 기기에 계속 표시된다.
    public void reassignTo(String userId, String platform) {
        this.userId = userId;
        this.platform = platform;
    }

    public static FcmToken of(String userId, String token, String platform) {
        return FcmToken.builder()
                .userId(userId)
                .token(token)
                .platform(platform)
                .build();
    }
}