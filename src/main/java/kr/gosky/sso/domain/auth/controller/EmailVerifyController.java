package kr.gosky.sso.domain.auth.controller;

import jakarta.validation.Valid;
import kr.gosky.sso.domain.auth.dto.EmailSendRequest;
import kr.gosky.sso.domain.auth.dto.EmailVerifyRequest;
import kr.gosky.sso.domain.auth.service.EmailVerifyService;
import kr.gosky.sso.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/email")
@RequiredArgsConstructor
public class EmailVerifyController {

    private final EmailVerifyService emailVerifyService;

    // 이메일 인증 코드 발송
    // 200 OK — 발송 성공
    // 404 Not Found — 존재하지 않는 이메일
    // 409 Conflict — 이미 인증된 이메일
    @PostMapping("/send")
    public ApiResponse<Void> send(@Valid @RequestBody EmailSendRequest request) {
        emailVerifyService.sendVerificationCode(request);
        return ApiResponse.ok("인증 코드가 발송되었습니다.");
    }

    // 이메일 인증 코드 검증
    // 200 OK — 인증 성공
    // 400 Bad Request — 코드 불일치 또는 만료
    // 404 Not Found — 존재하지 않는 이메일
    @PostMapping("/verify")
    public ApiResponse<Void> verify(@Valid @RequestBody EmailVerifyRequest request) {
        emailVerifyService.verifyCode(request);
        return ApiResponse.ok("이메일 인증이 완료되었습니다.");
    }
}
