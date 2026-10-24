package kr.silverbridge.main.domain.anomaly.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 보호자 이상감지 이력의 카메라 필터.
 *
 * <p>"삭제된 카메라"는 상황의 {@code session_id}에 해당하는 카메라 행이 더는 없다는 뜻이다(이력 응답의
 * {@code cameraLabel = null}과 같은 기준). 삭제 뒤 같은 기기로 다시 등록하면 새 세션이 발급되므로 옛 이력이
 * 다시 "있는 카메라"가 되지 않는다. 생략하면 전체다.</p>
 */
@Schema(description = "카메라 필터 (생략 시 전체) - EXISTING 지금 등록된 카메라 · DELETED 삭제된 카메라")
public enum AnomalyCameraFilter {

    EXISTING,
    DELETED
}
