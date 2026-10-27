package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 관리자 대시보드 - 운영 현황 탭.
 *
 * <p>회원 수는 <b>ADMIN을 제외</b>한다(운영자는 서비스 이용자가 아니다). 데이터가 0건이어도 키는 항상
 * 존재한다(0 또는 빈 배열).</p>
 */
@Schema(description = "관리자 대시보드 - 운영 현황")
public record AdminOperationDashboardResponse(

        @Schema(description = "전체 회원 수 (ADMIN 제외)", example = "13")
        long totalUsers,

        @Schema(description = "오늘(KST) 신규 가입 수", example = "2")
        long newUsersToday,

        @Schema(description = "회원 구성")
        MemberComposition memberComposition,

        @Schema(description = "카메라 현황")
        Cameras cameras,

        @Schema(description = "수락 대기 중인 연결 요청 수", example = "4")
        long pendingConnections,

        @Schema(description = "미답변 문의")
        UnansweredInquiries unansweredInquiries,

        @Schema(description = "최근 7일 가입 추이(KST). 가입이 0건인 날도 항목이 있습니다")
        List<SignupPoint> signupTrend,

        @Schema(description = "처리 대기 항목")
        PendingItems pendingItems,

        @Schema(description = "이 응답을 만든 시각(KST, 오프셋 포함). 화면의 \"N분 전 확인\" 기준", example = "2026-10-27T14:05:00+09:00")
        OffsetDateTime generatedAt
) {

    @Schema(description = "회원 구성")
    public record MemberComposition(
            @Schema(description = "피보호자 수", example = "6") long wards,
            @Schema(description = "보호자 수", example = "7") long guardians
    ) {
    }

    @Schema(description = "카메라 현황")
    public record Cameras(
            @Schema(description = "등록된 카메라 대수", example = "29") long registered,
            @Schema(description = "카메라를 1대 이상 등록한 피보호자 수", example = "6") long wards,
            @Schema(description = """
                    서비스 전체 카메라 등록 상한(camera.max-total). 상한이 꺼져 있으면 null.

                    `registered`(대)·`wards`(명)·`maxTotal`(대)은 단위가 다르니 "29 / 34대"처럼 섞어 쓰지 마세요.
                    "등록 대수 / 상한"은 `registered / maxTotal`입니다.
                    """, example = "3", nullable = true) Integer maxTotal
    ) {
    }

    /**
     * 미답변 문의.
     *
     * <p>{@code longestWaitingHours}가 {@code null}이면 <b>대기 중인 문의가 없다</b>는 뜻이다.
     * 0으로 채우면 "방금 들어온 문의가 있다"와 구분되지 않는다.</p>
     */
    @Schema(description = "미답변 문의")
    public record UnansweredInquiries(
            @Schema(description = "미답변 건수", example = "2")
            long count,

            @Schema(description = "가장 오래 기다린 문의의 대기 시간(시간). 대기 문의가 없으면 null",
                    example = "31", nullable = true)
            Long longestWaitingHours
    ) {
    }

    @Schema(description = "날짜별 가입 수")
    public record SignupPoint(
            @Schema(description = "날짜(KST)", example = "2026-08-25") LocalDate date,
            @Schema(description = "가입 수", example = "1") long count
    ) {
    }

    @Schema(description = "처리 대기 항목")
    public record PendingItems(
            @Schema(description = "수락 대기 연결 요청", example = "4") long connectionRequests,
            @Schema(description = "오늘(KST) 접수된 문의", example = "1") long todayInquiries,
            @Schema(description = "발행 대기 공지 초안", example = "3") long announcementDrafts
    ) {
    }
}
