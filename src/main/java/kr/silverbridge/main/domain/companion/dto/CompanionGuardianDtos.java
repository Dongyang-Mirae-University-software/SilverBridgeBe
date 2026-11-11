package kr.silverbridge.main.domain.companion.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 보호자 "AI 기분 체크" 응답(2026-11-04, PR-B). 대답 원문·받아 적은 글은 없다(결정 B) - 질문 문장·기분 코드·횟수만.
 * 모르는 값은 null이다(0·정상으로 바꾸지 않는다, 화면 "확인할 수 없어요").
 */
public final class CompanionGuardianDtos {

    private CompanionGuardianDtos() {
    }

    @Schema(description = "피보호자 오늘 안부 대화 요약")
    public record TodayResponse(
            @Schema(description = "KST 날짜", example = "2026-11-04") String date,
            @Schema(description = "지금 기분 - 오늘 가장 최근에 마친 대화. 없으면 null") Current current,
            @Schema(description = "오늘 대답한 대화 수(대답이 1번 이상 있는 대화)", example = "2") int answeredCount,
            @Schema(description = "하루 시간대 수", example = "5") int slotCount,
            @Schema(description = "다음 안부 시간대(09:00 형식). 오늘 남은 시간대가 없으면 null", example = "15:00") String nextSlot,
            List<Slot> slots,
            @Schema(description = "확인할 일 - 걱정·우울이 3일 이상 이어지면 값, 아니면 null") Attention attention) {
    }

    public record Current(
            @Schema(example = "12:00") String slot,
            @Schema(description = "JOY·CALM·WORRY·DEPRESSED. 판단할 수 없으면 null") String mood,
            @Schema(description = "대화가 끝난 시각") OffsetDateTime endedAt,
            @Schema(description = "한 줄 요약 - 지금은 항상 null(AI 미구현). null이면 화면에서 숨긴다") String summary) {
    }

    public record Slot(
            @Schema(example = "09:00") String slot,
            @Schema(description = "그 시간대의 안부 질문 문장. 고를 질문이 없으면 null") String question,
            @Schema(description = "UPCOMING(예정) / AVAILABLE(지금 가능) / IN_PROGRESS(대화 중) / DONE(마침) / MISSED(지나감)")
            String status,
            @Schema(description = "JOY·CALM·WORRY·DEPRESSED. 마치지 않았거나 판단할 수 없으면 null") String mood,
            @Schema(description = "그 시간대 대화 ID('대화 보기'용). 대화가 없으면 null", example = "123") Long sessionId) {
    }

    public record Attention(
            @Schema(description = "가장 최근 날의 기분: WORRY 또는 DEPRESSED") String mood,
            @Schema(description = "이어진 일수(3 이상)", example = "3") int days) {
    }

    @Schema(description = "최근 7일 하루 기분")
    public record WeekResponse(@Schema(description = "오래된 날부터 오늘까지 7개") List<DayMood> days) {
    }

    public record DayMood(
            @Schema(description = "KST 날짜", example = "2026-11-11") String date,
            @Schema(description = "하루 기분(그날 마지막으로 판정된 대화) JOY·CALM·WORRY·DEPRESSED. 판정된 대화가 없으면 null")
            String mood) {
    }

    @Schema(description = "안부 대화 보기")
    public record SessionDetailResponse(
            @Schema(example = "123") long sessionId,
            @Schema(description = "KST 날짜", example = "2026-11-11") String date,
            @Schema(example = "12:00") String slot,
            @Schema(description = "그 대화의 안부 질문 문장") String question,
            @Schema(description = "OPEN(대화 중)·FINISHED(마침)·LOST(끊김)") String status,
            @Schema(description = "대화 기분 JOY·CALM·WORRY·DEPRESSED. 마치지 않았거나 판단할 수 없으면 null") String mood,
            @Schema(description = "테스트 대화면 true") boolean test,
            @Schema(description = "턴 수") int turnCount,
            @Schema(description = "걱정·우울로 판정된 턴 수") int negativeCount,
            @Schema(description = "이 대화로 위험 알림이 보호자에게 나갔으면 true") boolean riskAlerted,
            OffsetDateTime startedAt,
            @Schema(description = "끝난 시각. 대화 중이면 null") OffsetDateTime endedAt,
            @Schema(description = "턴 목록(턴 번호 순). 기분은 항상, 글은 transcript=AVAILABLE일 때만") List<Turn> turns,
            @Schema(description = "AVAILABLE(대화 내용 있음) / NO_CONSENT(어르신이 대화 내용 저장에 동의하지 않음) / "
                    + "EXPIRED(보관 기간 30일이 지나 지움)") String transcript) {
    }

    public record Turn(
            @Schema(example = "1") int turnNo,
            @Schema(description = "턴 기분 JOY·CALM·WORRY·DEPRESSED. 판단할 수 없으면 null") String mood,
            @Schema(description = "이 대답 뒤에 말벗이 한 말. 동의 없음·만료면 null") String botText,
            @Schema(description = "어르신 대답(음성이면 받아 적은 글). 동의 없음·만료·받아 적지 못함이면 null") String answerText) {
    }
}
