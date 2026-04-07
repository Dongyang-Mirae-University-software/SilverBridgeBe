package kr.silverbridge.main.domain.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class FindEmailResponse {

    // 마스킹된 이메일 (예: us**@example.com)
    private String maskedEmail;
}
