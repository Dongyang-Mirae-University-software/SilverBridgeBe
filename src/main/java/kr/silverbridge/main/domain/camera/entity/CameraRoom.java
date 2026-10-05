package kr.silverbridge.main.domain.camera.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * 카메라를 둘 수 있는 방 목록(2026-10-05 사용자 결정). 카메라 {@code label}은 이 중 하나만 허용하고,
 * 한 피보호자 안에서 방마다 카메라는 1대다(DB {@code uq_camera_ward_label}).
 *
 * <p>DB에는 enum 이름이 아니라 한글 방 이름({@link #getLabel()})을 그대로 저장한다 - 화재 알림 문구의 위치
 * ("…님 댁 <b>거실</b>에서")와 이상감지 이력·관리자 검색이 이 문자열을 쓰기 때문이다. 방을 추가할 때는 여기에 값만 더하면
 * 되고 마이그레이션은 필요 없다(DB CHECK를 두지 않았다). 순서는 화면 표시 순서다.</p>
 */
@Getter
@RequiredArgsConstructor
public enum CameraRoom {

    LIVING_ROOM("거실"),
    BEDROOM("침실"),
    KITCHEN("주방"),
    BATHROOM("화장실"),
    ENTRANCE("현관"),
    BALCONY("베란다"),
    SMALL_ROOM("작은방"),
    SMALL_ROOM_2("작은방2");

    private final String label;

    public static Optional<CameraRoom> fromLabel(String label) {
        if (label == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(room -> room.label.equals(label)).findFirst();
    }
}
