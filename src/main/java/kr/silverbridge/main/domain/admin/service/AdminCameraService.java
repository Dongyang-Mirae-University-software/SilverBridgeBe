package kr.silverbridge.main.domain.admin.service;

import kr.silverbridge.main.domain.admin.dto.AdminCameraItem;
import kr.silverbridge.main.domain.admin.dto.AdminCameraLiveStatus;
import kr.silverbridge.main.domain.admin.dto.AdminCameraStatusFilter;
import kr.silverbridge.main.domain.admin.dto.AdminCameraSummaryResponse;
import kr.silverbridge.main.domain.admin.support.AdminPaging;
import kr.silverbridge.main.domain.anomaly.client.AiLiveStreamSubscriber;
import kr.silverbridge.main.domain.camera.entity.Camera;
import kr.silverbridge.main.domain.camera.repository.CameraRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 관리자 디바이스(카메라) 관리 - 조회 전용(2026-10-23).
 *
 * <p><b>목록을 메모리에서 거르고 자른다.</b> 상태(LIVE·OFFLINE)가 DB가 아니라 AI 구독 집합(메모리)에서 나오므로
 * DB 페이지를 먼저 자르면 필터 뒤 개수·페이지가 어긋난다. 서비스 전체 등록 상한 {@code camera.max-total}이 3대라
 * 전체를 읽어도 비용이 없다 - 상한을 수십 대 이상으로 올리면 이 방식부터 다시 볼 것.</p>
 *
 * <p><b>상태 기준은 대시보드와 같은 AI 구독 집합</b>이다. 구독 = AI가 라이브로 보고했고 우리가 감시(subscribe) 중.
 * AI 연결이 끊겼거나 재접속 뒤 첫 목록을 아직 받지 못했으면 상태는 {@code null}(확인 불가)이다 - 끊김으로 채우면
 * 우리 수신기의 장애가 현장 카메라 전멸로 보인다.</p>
 *
 * <p>탈퇴 처리 중(INACTIVE)인 피보호자의 카메라는 뺀다(회원 관리 목록과 같은 모집단, 곧 CASCADE로 지워진다).
 * 조회라 감사 로그는 남기지 않고, 로그에는 adminId·건수·소요 시간만 남긴다(주소·이름 금지).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminCameraService {

    private static final Comparator<AdminCameraItem> NEWEST_FIRST = Comparator
            .comparing(AdminCameraItem::registeredAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(AdminCameraItem::cameraId, Comparator.reverseOrder());

    private final AiLiveStreamSubscriber aiLiveStreamSubscriber;
    private final CameraRepository cameraRepository;
    private final UserRepository userRepository;

    @Value("${camera.max-total:3}")
    private int maxTotal;

    public PageResponse<AdminCameraItem> getCameras(String adminId, AdminCameraStatusFilter status, String q,
                                                    int page, int size) {
        long startedAt = System.nanoTime();
        AdminCameraStatusFilter filter = AdminCameraStatusFilter.orDefault(status);
        Optional<Set<String>> live = aiLiveStreamSubscriber.liveSessionSnapshot();
        if (filter != AdminCameraStatusFilter.ALL && live.isEmpty()) {
            // 모르는데 빈 목록을 주면 "끊긴 카메라 0대"·"정상 0대"로 정반대로 읽힌다
            throw new CustomException(ErrorCode.CAMERA_LIVE_STATUS_UNKNOWN);
        }

        String keyword = normalize(q);
        List<AdminCameraItem> matched = visibleCameras(live).stream()
                .filter(item -> filter.matches(item.liveStatus()))
                .filter(item -> keyword == null || matchesKeyword(item, keyword))
                .sorted(NEWEST_FIRST)
                .toList();

        PageRequest pageable = AdminPaging.of(page, size);
        int from = (int) Math.min(pageable.getOffset(), matched.size());
        int to = Math.min(from + pageable.getPageSize(), matched.size());
        PageResponse<AdminCameraItem> response =
                PageResponse.of(new PageImpl<>(matched.subList(from, to), pageable, matched.size()));

        log.info("[ADMIN-CAMERA] 목록 조회: adminId={}, 결과={}건, 소요={}ms",
                adminId, matched.size(), (System.nanoTime() - startedAt) / 1_000_000);
        return response;
    }

    public AdminCameraSummaryResponse getSummary() {
        Optional<Set<String>> live = aiLiveStreamSubscriber.liveSessionSnapshot();
        List<AdminCameraItem> cameras = visibleCameras(live);
        Long liveCount = live.isEmpty() ? null
                : cameras.stream().filter(item -> item.liveStatus() == AdminCameraLiveStatus.LIVE).count();
        Long offlineCount = liveCount == null ? null : cameras.size() - liveCount;

        return new AdminCameraSummaryResponse(
                live.isPresent(),
                cameras.size(),
                maxTotal > 0 ? maxTotal : null,
                liveCount,
                offlineCount,
                userRepository.countByStatusAndRole(Status.ACTIVE, Role.WARD),
                userRepository.countWardsWithoutCamera(Role.WARD, Status.ACTIVE));
    }

    /** 탈퇴 처리 중 피보호자를 뺀 전체 카메라(꺼 둔 카메라 포함 - 꺼도 감지는 계속되므로 실제 송출 여부를 보여준다). */
    private List<AdminCameraItem> visibleCameras(Optional<Set<String>> live) {
        List<Camera> cameras = cameraRepository.findAll();
        Set<String> wardIds = cameras.stream().map(Camera::getWardId).collect(Collectors.toSet());
        Map<String, User> wards = userRepository.findAllById(wardIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        return cameras.stream()
                .filter(camera -> {
                    User ward = wards.get(camera.getWardId());
                    return ward == null || ward.getStatus() != Status.INACTIVE;
                })
                .map(camera -> toItem(camera, wards.get(camera.getWardId()), live))
                .toList();
    }

    private static AdminCameraItem toItem(Camera camera, User ward, Optional<Set<String>> live) {
        AdminCameraLiveStatus status = live
                .map(sessions -> sessions.contains(camera.getSessionId())
                        ? AdminCameraLiveStatus.LIVE : AdminCameraLiveStatus.OFFLINE)
                .orElse(null);
        return new AdminCameraItem(
                camera.getId(),
                camera.getSessionId(),
                camera.getLabel(),
                camera.getWardId(),
                ward == null ? null : ward.getName(),
                ward == null ? null : ward.getAddress(),
                camera.isActive(),
                camera.getCreatedAt() == null ? null
                        : camera.getCreatedAt().atZoneSameInstant(AdminDashboardClock.KST).toOffsetDateTime(),
                status);
    }

    /** 공백을 모두 지우고 소문자로 - 주소는 띄어쓰기가 사람마다 달라("역삼로123" vs "역삼로 123") 공백을 무시한다. */
    private static String normalize(String q) {
        if (!StringUtils.hasText(q)) {
            return null;
        }
        String stripped = q.replaceAll("\\s+", "").toLowerCase();
        return stripped.isEmpty() ? null : stripped;
    }

    private static boolean matchesKeyword(AdminCameraItem item, String keyword) {
        return contains(item.wardAddress(), keyword)
                || contains(item.room(), keyword)
                || contains(item.wardName(), keyword)
                || contains(item.sessionId(), keyword);
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.replaceAll("\\s+", "").toLowerCase().contains(keyword);
    }
}
