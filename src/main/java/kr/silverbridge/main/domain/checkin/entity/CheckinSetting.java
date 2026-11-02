package kr.silverbridge.main.domain.checkin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 피보호자 본인의 말벗 설정. 표정 분석 동의는 기본 꺼짐이고 본인만 바꾼다. */
@Entity
@Table(name = "checkin_setting", uniqueConstraints = {
        @UniqueConstraint(name = "uq_checkin_setting_ward", columnNames = {"ward_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CheckinSetting extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ward_id", nullable = false, length = 6)
    private String wardId;

    @Column(name = "face_consent", nullable = false)
    private boolean faceConsent;

    private CheckinSetting(String wardId, boolean faceConsent) {
        this.wardId = wardId;
        this.faceConsent = faceConsent;
    }

    public static CheckinSetting of(String wardId, boolean faceConsent) {
        return new CheckinSetting(wardId, faceConsent);
    }

    public void changeFaceConsent(boolean faceConsent) {
        this.faceConsent = faceConsent;
    }
}
