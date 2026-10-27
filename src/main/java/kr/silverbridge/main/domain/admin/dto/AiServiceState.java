package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 대시보드 AI 서비스 상태값. <b>확인하지 못하면 이 값이 아니라 null</b>이다 - "끊김"과 "모름"을 섞지 않는다.
 */
@Schema(description = "AI 서비스 상태 - OK 정상 · DISCONNECTED 연결 끊김(확인된 사실). 확인하지 못하면 null(확인 불가)")
public enum AiServiceState {
    OK,
    DISCONNECTED
}
