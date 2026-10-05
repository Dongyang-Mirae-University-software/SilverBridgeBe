package kr.silverbridge.main.domain.notification.service;

import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.jwt.JwtProperties;
import kr.silverbridge.main.global.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 세션 만료(자동 로그아웃) 뒤 이 기기의 FCM 토큰 해제 (XAREA-G01, 2026-10-05).
 *
 * <p><b>문제</b>: 토큰 삭제 API({@code DELETE /api/notifications/fcm-token})는 인증이 필요해, 세션이 만료돼
 * 자동 로그아웃되는 기기는 자기 FCM 토큰을 지울 수 없었다. 그 기기에는 다음 사용자가 로그인해 토큰을 재등록하거나
 * (소유자 갱신 M-S2-2) 60일 유휴 정리가 돌 때까지 이전 사용자의 SOS·화재 알림(이름·장소 포함)이 계속 표시된다.</p>
 *
 * <p><b>방법</b>: 마지막으로 쓰던 access token을 <b>만료돼도</b> 받되 서명·typ은 검사해 본인을 확인하고,
 * <b>그 사용자 소유 토큰만</b> 지운다(L-S2-3 본인 소유 삭제 원칙 그대로). 만료 허용 범위는 refresh token 수명
 * 만큼이다 - 그보다 오래된 토큰은 그 세션이 살아 있었을 수 없는 시점이다.</p>
 *
 * <p>로그아웃 블랙리스트·무효화(비밀번호 변경·정지)는 보지 않는다 - 이 경로가 할 수 있는 일은
 * "본인 기기로 가는 알림을 끊는 것"뿐이라, 무효화된 토큰으로 불려도 잃는 것이 없다. 이 메서드를 인증 수단으로
 * 넓혀 쓰지 말 것.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FcmTokenReleaseService {

    private final FcmService fcmService;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;

    /**
     * @throws CustomException INVALID_TOKEN - 우리가 서명한 access token이 아니거나 허용 범위보다 오래 전에 만료됨
     */
    public void release(String accessToken, String fcmToken) {
        String userId = jwtTokenProvider
                .getAccessTokenSubjectAllowingExpired(accessToken, jwtProperties.getRefreshTokenExpiration())
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_TOKEN));
        // 본인 소유가 아니면 아무것도 지우지 않는다(조용히 200 - 기존 삭제 API와 같은 응답)
        fcmService.deleteToken(userId, fcmToken);
        log.info("[FCM-TOKEN] 세션 만료 후 기기 토큰 해제 요청 처리: userId={}", userId);
    }
}
