package kr.silverbridge.main.global.client;

import com.solapi.sdk.message.exception.SolapiMessageNotReceivedException;
import com.solapi.sdk.message.model.FailedMessage;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Solapi 실패를 로그에 남길 때 쓸 수 있는 값만 꺼낸다 (QA XCUT-G11 후속, 알림 이력 불변 규칙 ②).
 *
 * <p>{@code getFailedMessageList()}의 문자열 표현에는 수신·발신 번호와 사유 원문이 들어 있고, 예외 메시지도 SDK가
 * 응답 원문을 싣는다. 로그(컨테이너 stdout = 수집 경로)에는 <b>예외 클래스명과 발송사 상태 코드</b>만 남긴다.
 * 상태 코드도 형식(영숫자 16자 이하)이 맞는 것만 통과시켜 다른 값이 섞여 들어오지 않게 한다.</p>
 */
public final class SolapiFailureCodes {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{1,16}");

    private SolapiFailureCodes() {}

    /** 접수 거부 건의 발송사 상태 코드(중복 제거, 형식이 맞는 것만). 없으면 빈 문자열. */
    public static String statusCodes(SolapiMessageNotReceivedException e) {
        List<FailedMessage> failed = e.getFailedMessageList();
        if (failed == null || failed.isEmpty()) {
            return "";
        }
        return failed.stream()
                .filter(Objects::nonNull)
                .map(FailedMessage::getStatusCode)
                .filter(code -> code != null && CODE.matcher(code).matches())
                .distinct()
                .collect(Collectors.joining(","));
    }
}
