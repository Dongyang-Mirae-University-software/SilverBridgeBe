package kr.silverbridge.main.domain.sos.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import lombok.*;

/**
 * 피보호자별 SOS 동작 설정. 사용자당 한 행({@code user_id} UNIQUE).
 *
 * <p>행이 없는 사용자는 "미설정"으로 보고 {@code SosSettingService}의 기본값
 * ({@link SosAction#CALL_119_AND_NOTIFY})을 따른다. 따라서 기존 사용자에 대한 백필 마이그레이션이 필요 없다
 * ({@code UserNotificationSetting}과 동일한 방식).</p>
 *
 * <p>users FK는 {@code ON DELETE CASCADE}(V32)라 회원 탈퇴(hard delete) 시 자동 정리된다.</p>
 */
@Entity
@Table(name = "sos_setting", uniqueConstraints = {
        @UniqueConstraint(name = "uq_sos_setting_user", columnNames = {"user_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class SosSetting extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 6)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sos_action", nullable = false, length = 30)
    private SosAction sosAction;

    public static SosSetting of(String userId, SosAction sosAction) {
        return SosSetting.builder()
                .userId(userId)
                .sosAction(sosAction)
                .build();
    }

    public void updateSosAction(SosAction sosAction) {
        this.sosAction = sosAction;
    }
}
