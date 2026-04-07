package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.*;
import kr.silverbridge.main.domain.admin.entity.SsoClient;
import kr.silverbridge.main.domain.admin.repository.SsoClientRepository;
import kr.silverbridge.main.domain.auth.repository.AccessLogRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final AccessLogRepository accessLogRepository;
    private final SsoClientRepository ssoClientRepository;

    // 사용자 목록 조회 (페이징)
    @Transactional(readOnly = true)
    public Page<UserSummaryResponse> getUsers(Pageable pageable) {
        return userRepository.findAll(pageable)
                .map(UserSummaryResponse::from);
    }

    // 사용자 상세 조회
    @Transactional(readOnly = true)
    public UserDetailResponse getUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        return UserDetailResponse.from(user);
    }

    // 사용자 상태 변경 (활성화 / 비활성화)
    @Transactional
    public void updateUserStatus(String userId, UserStatusUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        switch (request.getStatus()) {
            case ACTIVE   -> user.activate();
            case INACTIVE -> user.deactivate();
        }
    }

    // 접속 로그 조회 (페이징)
    @Transactional(readOnly = true)
    public Page<AccessLogResponse> getAccessLogs(Pageable pageable) {
        return accessLogRepository.findAll(pageable)
                .map(AccessLogResponse::from);
    }

    // 대시보드 통계 조회
    @Transactional(readOnly = true)
    public DashboardResponse getDashboard() {
        OffsetDateTime todayStart = LocalDate.now().atStartOfDay().atOffset(OffsetDateTime.now().getOffset());

        return DashboardResponse.builder()
                .totalUsers(userRepository.count())
                .totalClients(ssoClientRepository.count())
                .totalLogs(accessLogRepository.count())
                .todayUsers(accessLogRepository.countByActionAndCreatedAtAfter("LOGIN", todayStart))
                .build();
    }

    // 서비스 등록
    // client_secret 미입력 시 UUID 자동 생성, 등록 응답에서 한 번만 노출
    @Transactional
    public ClientRegisterResponse registerClient(ClientRegisterRequest request) {
        if (ssoClientRepository.existsByClientId(request.getClientId())) {
            throw new CustomException(ErrorCode.CLIENT_ID_ALREADY_EXISTS);
        }

        validateRedirectUri(request.getRedirectUri());

        String secret = (request.getClientSecret() == null || request.getClientSecret().isBlank())
                ? UUID.randomUUID().toString()
                : request.getClientSecret();

        SsoClient client = SsoClient.builder()
                .clientId(request.getClientId())
                .clientName(request.getClientName())
                .clientSecret(secret)
                .redirectUri(request.getRedirectUri())
                .isActive(true)
                .build();

        ssoClientRepository.save(client);

        return ClientRegisterResponse.builder()
                .clientId(client.getClientId())
                .clientName(client.getClientName())
                .clientSecret(secret)   // 등록 시 한 번만 반환
                .redirectUri(client.getRedirectUri())
                .build();
    }

    // 등록된 서비스 목록 조회 (페이징)
    @Transactional(readOnly = true)
    public Page<ClientSummaryResponse> getClients(Pageable pageable) {
        return ssoClientRepository.findAll(pageable)
                .map(ClientSummaryResponse::from);
    }

    // 서비스 삭제
    @Transactional
    public void deleteClient(String clientId) {
        SsoClient client = ssoClientRepository.findByClientId(clientId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLIENT_NOT_FOUND));
        ssoClientRepository.delete(client);
    }

    // redirect_uri gosky.kr 도메인 검증
    private void validateRedirectUri(String redirectUri) {
        try {
            String host = new URI(redirectUri).getHost();
            if (host == null || !host.endsWith("gosky.kr")) {
                throw new CustomException(ErrorCode.INVALID_REDIRECT_URI);
            }
        } catch (URISyntaxException e) {
            throw new CustomException(ErrorCode.INVALID_REDIRECT_URI);
        }
    }
}
