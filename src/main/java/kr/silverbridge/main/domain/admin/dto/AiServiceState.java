package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 대시보드 AI 서비스 상태값. <b>확인하지 못하면 이 값이 아니라 null</b>이다 - "끊김"과 "모름"을 섞지 않는다.
 */
@Schema(description = "AI 서비스 상태 - OK 정상 · DISCONNECTED 연결 끊김(확인된 사실) · DISABLED 운영자가 꺼 둠(킬 스위치). 확인하지 못하면 null(확인 불가)")
public enum AiServiceState {
    OK,
    DISCONNECTED,
    /** 운영 설정(킬 스위치)으로 꺼 둠 - AI가 살아 있어도 사용자는 쓸 수 없으므로 "정상"과 나눈다(2026-10-27 점검 M-1). */
    DISABLED
}
