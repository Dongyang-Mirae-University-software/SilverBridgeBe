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

    public static FcmToken of(String userId, String token, String platform) {
        return FcmToken.builder()
                .userId(userId)
                .token(token)
                .platform(platform)
                .build();
    }
}