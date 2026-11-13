package kr.silverbridge.main.domain.companion.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 안부 말벗 기기(피보호자) API 요청·응답. 대답 글·받아 적은 글·말벗 응답 문장은 피보호자가 동의했을 때만 저장한다(30일, 2026-11-11). */
public final class CompanionDtos {

    private CompanionDtos() {
    }

    @Schema(description = "글 대답")
    public record ReplyRequest(@Schema(description = "대답 글 1~500자") String text) {
    }

    @Schema(description = "오늘 안부 대화 현황")
    public record TodayResponse(
            @Schema(description = "KST 날짜", example = "2026-11-04") String date,
            @Schema(description = "지금 열 수 있는 시간대(09:00 형식). 없거나 말벗이 꺼져 있으면 null") String currentSlot,
            List<SlotStatus> slots,
            @Schema(description = "최종 QA 테스트 계정이면 true - 시간대 선택 시작·테스트 안내 푸시를 쓸 수 있다") boolean testMode) {
    }

    @Schema(description = "테스트 계정의 시간대 선택(시작·안내 푸시)")
    public record TestSlotRequest(@Schema(description = "09:00·12:00·15:00·18:00·21:00", example = "15:00") String slot) {
    }

    public record SlotStatus(
            @Schema(example = "09:00") String slot,
            @Schema(description = "UPCOMING(아직) / AVAILABLE(지금 가능) / IN_PROGRESS(진행 중) / DONE(마침) / MISSED(지나감)")
            String status,
            @Schema(description = "JOY·CALM·WORRY·DEPRESSED. 판단할 수 없으면 null - 화면에는 '확인할 수 없어요'(0·정상으로 표시 금지)")
            String mood) {
    }

    @Schema(description = "열린 안부 대화")
    public record SessionResponse(
            @Schema(description = "대화 번호 - 이후 호출에 쓴다") long sessionId,
            @Schema(example = "09:00") String slot,
            @Schema(description = "기기가 읽어 줄 문장. 새 대화는 첫 질문, 이어 가는 대화는 말벗이 마지막으로 한 말"
                    + "(AI가 답하지 않거나 아직 턴이 없으면 첫 질문)") String question,
            @Schema(description = "최대 턴 수. 알 수 없으면 null") Integer maxTurns,
            @Schema(description = "이미 열려 있던 대화를 이어 가는 것이면 true") boolean resumed,
            @Schema(description = "이미 마친 대답 수(마지막 턴 번호). 새 대화는 0, 이어 가는 대화는 지금까지 기록된 턴 수 - "
                    + "기기는 다음 대답 번호를 turnCount + 1로 보여 준다", example = "0") int turnCount,
            @Schema(description = "첫 질문의 말벗 목소리 - GET sessions/{sessionId}/audio/{index}로 받는다. "
                    + "AI가 준비하지 않았거나 이어 가는 대화면 null(기기가 직접 읽어 준다)") UtteranceAudio audio) {

        public SessionResponse(long sessionId, String slot, String question, Integer maxTurns, boolean resumed,
                               int turnCount) {
            this(sessionId, slot, question, maxTurns, resumed, turnCount, null);
        }
    }

    /** 말벗 목소리 위치(2026-11-13). AI가 준 소리 번호만 옮긴다 - 소리 자체는 BE가 저장하지 않는다. */
    @Schema(description = "말벗 목소리 위치")
    public record UtteranceAudio(@Schema(description = "소리 번호(0 이상)", example = "1") int index) {
    }

    @Schema(description = "한 턴 결과")
    public record TurnResponse(
            int turnNo,
            @Schema(description = "말벗 응답 문장(기기가 읽어 준다). 대화 내용 저장에 동의한 경우에만 저장(30일 뒤 삭제)") String reply,
            @Schema(description = "true면 대화가 끝났다(결과는 서버가 이미 저장했다)") boolean ended,
            @Schema(description = "MAX_TURNS·NO_RESPONSE·USER_STOP·AVOIDED(어르신이 이야기를 원하지 않아 말벗이 마무리). 진행 중이면 null") String endReason,
            @Schema(description = "음성 대답을 받아 적은 글(음성 턴만). 대화 내용 저장에 동의한 경우에만 저장(30일 뒤 삭제)") String transcript,
            @Schema(description = "AI 턴 판정 결과 - 테스트 계정에만 실린다(일반 계정은 필드 없음). 저장·로그 없음")
            @JsonInclude(JsonInclude.Include.NON_NULL) TurnAnalysis analysis,
            @Schema(description = "응답 문장의 말벗 목소리 - GET sessions/{sessionId}/audio/{index}로 받는다. "
                    + "AI가 준비하지 않았으면 null(기기가 직접 읽어 준다)") UtteranceAudio audio) {

        public TurnResponse(int turnNo, String reply, boolean ended, String endReason, String transcript) {
            this(turnNo, reply, ended, endReason, transcript, null, null);
        }

        public TurnResponse(int turnNo, String reply, boolean ended, String endReason, String transcript,
                            TurnAnalysis analysis) {
            this(turnNo, reply, ended, endReason, transcript, analysis, null);
        }
    }

    /**
     * 테스트 계정 화면 확인용 턴 판정(2026-11-10). AI가 준 값을 그대로 옮기고 만들어 내지 않는다 - AI가 주지 않는 값은
     * 없다(글 기분 코드는 AI가 따로 주지 않아 싣지 않는다). DB·로그·알림 어디에도 남기지 않는다.
     */
    @Schema(description = "테스트 계정용 턴 판정(AI 값 그대로)")
    public record TurnAnalysis(
            @Schema(description = "AI turnResult.label(기쁨·평온·슬픔·우울·판단 보류 등 - WORRY는 슬픔, 무응답이면 null)") String turnLabel,
            @Schema(description = "AI turnResult.source(TEXT·FACE·NO_RESPONSE 등)") String turnSource,
            @Schema(description = "AI face 값 그대로(usable·label·confidence·frameCount·totalFrames·facesFound)")
            Map<String, Object> face,
            @Schema(description = "턴 위험 = AI 최상위 riskFlag 또는 signals.riskFlag") boolean riskFlag,
            @Schema(description = "AI signals 그대로. 신호가 없는 턴(무응답·되묻기)은 null") Map<String, Object> signals,
            @Schema(description = "AI textMood 그대로(글 기분 분류: label·turnLabel·confidence·usable·reason). "
                    + "무응답·되묻기 턴, AI가 주지 않으면 null")
            Map<String, Object> textMood) {
    }

    @Schema(description = "마친 대화 결과")
    public record ResultResponse(
            long sessionId,
            @Schema(example = "09:00") String slot,
            @Schema(description = "MAX_TURNS·NO_RESPONSE·USER_STOP·AVOIDED(어르신이 이야기를 원하지 않아 말벗이 마무리)") String endReason,
            @Schema(description = "JOY·CALM·WORRY·DEPRESSED. 판단할 수 없으면 null") String mood,
            int turnCount,
            @Schema(description = "대화 판정 결과 - 테스트 계정에만 실린다(일반 계정은 필드 없음)")
            @JsonInclude(JsonInclude.Include.NON_NULL) ResultAnalysis analysis) {

        public ResultResponse(long sessionId, String slot, String endReason, String mood, int turnCount) {
            this(sessionId, slot, endReason, mood, turnCount, null);
        }
    }

    /** 테스트 계정용 대화 판정(2026-11-10). 저장된 결과 값이라 이미 마친 대화를 다시 불러도 같다. */
    @Schema(description = "테스트 계정용 대화 판정")
    public record ResultAnalysis(
            @Schema(description = "대화 기분 JOY·CALM·WORRY·DEPRESSED, 판단 불가 null") String mood,
            int turnCount,
            Integer answeredCount,
            Integer noResponseCount,
            @Schema(description = "대화 중 위험 신호가 있었는가") boolean riskFlag,
            @Schema(description = "URGENT·INFO(AI 값), 없으면 null") String alertLevel,
            @Schema(description = "테스트 대화면 true") boolean test) {
    }

    @Schema(description = "대화 내용 저장 동의 바꾸기")
    public record TranscriptConsentRequest(
            @Schema(description = "true = 동의, false = 철회(저장된 대화 내용을 바로 지움)") Boolean consent) {
    }

    @Schema(description = "대화 내용 저장 동의 상태")
    public record TranscriptConsentResponse(
            @Schema(description = "동의했으면 true. 기본 false") boolean consented,
            @Schema(description = "처음 동의한 시각. 동의하지 않았으면 null") OffsetDateTime consentedAt,
            @Schema(description = "대화 내용 보관 일수(지나면 지움)", example = "30") int retentionDays,
            @Schema(description = "동의 받을 때 화면에 그대로 보여 줄 고지 4항목(목적·항목·보유 기간·거부 권리와 불이익, "
                    + "개인정보 보호법 제22조·제23조). 순서대로 표시") List<String> notice) {
    }
}
