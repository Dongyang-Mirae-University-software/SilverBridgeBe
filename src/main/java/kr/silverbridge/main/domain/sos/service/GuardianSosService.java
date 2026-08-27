package kr.silverbridge.main.domain.sos.service;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.sos.dto.SosHistoryItem;
import kr.silverbridge.main.domain.sos.entity.SosEvent;
import kr.silverbridge.main.domain.sos.repository.SosEventRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 보호자용 SOS 이력 조회 서비스.
 *
 * <p>SOS 발생({@link SosService})과 분리한다 - 발생은 피보호자의 긴급 경로라 부수효과를 최소화해야 하고,
 * 이쪽은 보호자의 사후 조회 경로다.</p>
 *
 * <p><b>인가 원칙</b>: 보호자는 <b>현재 ACTIVE 연결</b>인 피보호자의 이력만 볼 수 있다. 연결이 해제되면
 * 과거 이력도 보이지 않는다(피보호자 개인정보가 연결 종료 후에도 남지 않게 한다). 연결 없는 대상 접근은
 * 404 위장 대신 403으로 그대로 안내하고 {@code [IDOR-ATTEMPT]} WARN을 남긴다(2026-07-14 정책).</p>
 *
 * <p><b>조회 전용이다</b> - 보호자가 처리 결과를 남기는 ACK 기능(2026-07-30)은 철회했다(2026-08-26, V39).
 * 이력은 "언제·어떤 경로로 발생했는가"까지만 답한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardianSosService {

    /** 페이지 크기 상한 - 과대 요청으로 전체 이력을 한 번에 끌어가는 것을 막는다. */
    private static final int MAX_PAGE_SIZE = 50;

    private final SosEventRepository sosEventRepository;
    private final ConnectionService connectionService;
    private final UserRepository userRepository;

    /**
     * 보호자용 SOS 이력 조회(발생 최신순).
     *
     * @param guardianId 조회하는 보호자 ID (인증 주체)
     * @param wardId     특정 피보호자만 볼 때 지정. {@code null}·공백이면 ACTIVE 연결된 피보호자 전원의 이력을 합친다
     * @throws CustomException {@code SOS_NOT_AUTHORIZED} - wardId를 지정했으나 ACTIVE 연결이 아닐 때
     */
    @Transactional(readOnly = true)
    public PageResponse<SosHistoryItem> getHistory(String guardianId, String wardId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size));
        List<String> wardIds = resolveVisibleWardIds(guardianId, wardId);
        if (wardIds.isEmpty()) {
            // 연결된 피보호자가 없으면 빈 페이지 - 보호자 카메라 목록(allowlist)과 같은 방식
            return PageResponse.of(Page.empty(pageable));
        }

        Page<SosEvent> events = sosEventRepository.findByWardIdInOrderByCreatedAtDesc(wardIds, pageable);
        Map<String, String> names = resolveWardNames(events.getContent());

        return PageResponse.of(events.map(event -> SosHistoryItem.of(event, names.get(event.getWardId()))));
    }

    /**
     * 조회 대상 피보호자 ID 목록을 인가 검증과 함께 결정한다. 이 메서드를 통과한 목록만 쿼리에 넘긴다.
     */
    private List<String> resolveVisibleWardIds(String guardianId, String wardId) {
        if (!StringUtils.hasText(wardId)) {
            return connectionService.getActiveWardIds(guardianId);
        }
        if (!connectionService.isActiveConnection(guardianId, wardId)) {
            log.warn("[IDOR-ATTEMPT] 연결되지 않은 피보호자 SOS 이력 조회 시도: guardianId={}, wardId={}",
                    guardianId, wardId);
            throw new CustomException(ErrorCode.SOS_NOT_AUTHORIZED);
        }
        return List.of(wardId);
    }

    /**
     * 이력에 등장하는 피보호자 이름을 한 번에 조회한다(건별 조회로 인한 N+1 회피).
     * 탈퇴로 사라진 사용자는 맵에 없어 {@code null}로 표시된다(관리자 문의 목록의 작성자 처리와 동일).
     *
     * <p>빈 결과로 {@code Map.of()}를 쓰지 않는다 - 탈퇴 피보호자의 익명 이력({@code wardId == null})만
     * 조회되면 호출부가 {@code get(null)}을 하는데, {@code Map.of()}는 null 키 조회에 NPE를 던진다.</p>
     */
    private Map<String, String> resolveWardNames(List<SosEvent> events) {
        Set<String> wardIds = events.stream()
                .map(SosEvent::getWardId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (wardIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userRepository.findAllById(wardIds).stream()
                .filter(user -> StringUtils.hasText(user.getName()))
                .collect(Collectors.toMap(User::getId, User::getName));
    }

    private int normalizeSize(int size) {
        if (size < 1) {
            return 1;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
