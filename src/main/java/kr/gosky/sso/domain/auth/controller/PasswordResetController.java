package kr.gosky.sso.domain.auth.controller;

import jakarta.validation.Valid;
import kr.gosky.sso.domain.auth.dto.PasswordResetConfirmRequest;
import kr.gosky.sso.domain.auth.dto.PasswordResetRequest;
import kr.gosky.sso.domain.auth.service.PasswordResetService;
import kr.gosky.sso.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/password")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    // 비밀번호 재설정 요청 — 이메일로 재설정 토큰 발송
    // 200 OK — 발송 성공
    // 404 Not Found — 존재하지 않는 이메일
    @PostMapping("/reset-request")
    public ApiResponse<Void> requestReset(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.requestReset(request);
        return ApiResponse.ok("비밀번호 재설정 이메일이 발송되었습니다.");
    }

    // 비밀번호 재설정 확인 — 토큰 검증 후 새 비밀번호로 변경
    // 200 OK — 변경 성공
    // 400 Bad Request — 유효하지 않거나 만료된 토큰
    // 404 Not Found — 사용자 없음
    @PostMapping("/reset")
    public ApiResponse<Void> confirmReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirmReset(request);
        return ApiResponse.ok("비밀번호가 변경되었습니다.");
    }
}
