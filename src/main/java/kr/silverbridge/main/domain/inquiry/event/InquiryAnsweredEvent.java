package kr.silverbridge.main.domain.inquiry.event;

/**
 * 관리자가 문의에 답변을 등록한 직후 발행되는 이벤트.
 * 작성자(보호자)에게 답변 완료 알림(선택, 사용자 설정 따름)을 발송하기 위해 사용된다.
 *
 * @param inquiryId    답변된 문의 ID (클라이언트 딥링크용 data)
 * @param authorUserId 알림 수신자 = 문의 작성자(보호자) ID
 */
public record InquiryAnsweredEvent(
        Long inquiryId,
        String authorUserId
) {}
