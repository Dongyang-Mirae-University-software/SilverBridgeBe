package kr.silverbridge.main.domain.sos.service;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.sos.config.SosProperties;
import kr.silverbridge.main.domain.sos.dto.SosHistoryItem;
import kr.silverbridge.main.domain.sos.dto.SosHistoryPage;
import kr.silverbridge.main.domain.sos.entity.SosEvent;
import kr.silverbridge.main.domain.sos.entity.SosTriggerType;
import kr.silverbridge.main.domain.sos.repository.SosEventRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.response.PageOffsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    /** 사용자 ID 형식(영숫자 1~6자). 요청 wardId가 어긋나면 로그에 원문을 남기지 않는다. */
    private static final Pattern WARD_ID_FORMAT = Pattern.compile("[A-Za-z0-9]{1,6}");

    private final SosEventRepository sosEventRepository;
    private final ConnectionService connectionService;
    private final UserRepository userRepository;
    private final SosProperties sosProperties;

    /**
     * 보호자용 SOS 이력 조회(발생 최신순).
     *
     * <p>경로별 건수({@code counts})는 <b>필터·페이지와 무관하게 조회 범위 전체</b>를 센다(SOS-G12). 화면 탭 숫자를
     * 현재 페이지로 세면 2페이지 이후에만 있는 경로가 0건으로 보였다. 집계 범위도 목록과 같은 인가 목록이다 -
     * ACTIVE 연결이 아닌 피보호자의 건수는 섞이지 않는다.</p>
     *
     * @param guardianId  조회하는 보호자 ID (인증 주체)
     * @param wardId      특정 피보호자만 볼 때 지정. {@code null}·공백이면 ACTIVE 연결된 피보호자 전원의 이력을 합친다
     * @param triggerType 발생 경로 필터. {@code null}이면 전체
     * @throws CustomException {@code SOS_NOT_AUTHORIZED} - wardId를 지정했으나 ACTIVE 연결이 아닐 때
     */
    @Transactional(readOnly = true)
    public SosHistoryPage getHistory(String guardianId, String wardId, SosTriggerType triggerType,
                                     int page, int size) {
        int pageSize = normalizeSize(size);
        // 큰 page는 offset이 int를 넘어 500이었다 - 범위 안의 마지막 페이지로 맞춘다(PageOffsets)
        Pageable pageable = PageRequest.of(PageOffsets.clampPage(page, pageSize), pageSize);
        List<String> wardIds = resolveVisibleWardIds(guardianId, wardId);
        if (wardIds.isEmpty()) {
            // 연결된 피보호자가 없으면 빈 페이지 - 보호자 카메라 목록(allowlist)과 같은 방식
            return SosHistoryPage.of(Page.empty(pageable), SosHistoryPage.Counts.EMPTY);
        }

        Page<SosEvent> events = (triggerType == null)
                ? sosEventRepository.findByWardIdInOrderByCreatedAtDesc(wardIds, pageable)
                : sosEventRepository.findByWardIdInAndTriggerTypeOrderByCreatedAtDesc(wardIds, triggerType, pageable);
        Map<String, String> names = resolveWardNames(events.getContent());
        CalledGuardianNames called = resolveCalledGuardianNames(events.getContent());

        return SosHistoryPage.of(
                events.map(event -> SosHistoryItem.of(event, names.get(event.getWardId()))
                        .withCalledGuardianName(called.nameFor(event))),
                countByTriggerType(wardIds));
    }

    /**
     * 보호자용 SOS 이력 - 연타 묶음 단위 조회({@code group=true}, 2026-10-30).
     *
     * <p>같은 피보호자·같은 경로의 다음 누름이 <b>앞 누름과</b> {@code sos.history-group-gap-seconds}(기본 60초) 이내면
     * 한 칸으로 묶는다. 묶음은 DB 안에서 만든다 - 페이지로 자른 뒤 묶으면 경계에 걸친 묶음이 두 칸으로 갈린다.
     * 페이지·{@code totalElements}·{@code counts}는 모두 <b>묶음 수</b>다. 인가는 {@link #getHistory}와 같다.</p>
     *
     * <p>표시 전용이다 - 알림 쿨다운·푸시 문구의 "최근 10분 내 N번째"와 별개이며 이력 저장·알림에 관여하지 않는다.</p>
     *
     * @throws CustomException {@code SOS_NOT_AUTHORIZED} - wardId를 지정했으나 ACTIVE 연결이 아닐 때
     */
    @Transactional(readOnly = true)
    public SosHistoryPage getGroupedHistory(String guardianId, String wardId, SosTriggerType triggerType,
                                            int page, int size) {
        int pageSize = normalizeSize(size);
        Pageable pageable = PageRequest.of(PageOffsets.clampPage(page, pageSize), pageSize);
        List<String> wardIds = resolveVisibleWardIds(guardianId, wardId);
        if (wardIds.isEmpty()) {
            return SosHistoryPage.of(Page.empty(pageable), SosHistoryPage.Counts.EMPTY);
        }

        String type = (triggerType == null) ? "" : triggerType.name();
        int gap = sosProperties.effectiveHistoryGroupGapSeconds();
        SosHistoryPage.Counts counts = countGroupsByTriggerType(wardIds, gap);
        // 경로 필터가 없으면 전체 묶음 수 = 경로별 묶음 수의 합이라 같은 윈도 쿼리를 한 번 더 돌리지 않는다(점검 L-2)
        long total = (triggerType == null) ? counts.all() : sosEventRepository.countPressGroups(wardIds, type, gap);
        List<SosEventRepository.PressGroupRow> rows = sosEventRepository.findPressGroups(
                wardIds, type, gap, pageSize, pageable.getOffset());

        // 묶음 행은 ID만 준다 - 최신·최초 이력을 한 번에 읽어 기존 항목과 같은 매핑을 쓴다
        Set<Long> eventIds = rows.stream()
                .flatMap(row -> Stream.of(row.getLatestId(), row.getFirstId()))
                .collect(Collectors.toSet());
        Map<Long, SosEvent> events = sosEventRepository.findAllById(eventIds).stream()
                .collect(Collectors.toMap(SosEvent::getId, Function.identity()));
        Map<String, String> names = resolveWardNames(List.copyOf(events.values()));
        CalledGuardianNames called = resolveCalledGuardianNames(events.values());

        // 두 쿼리 사이에 이력이 지워졌으면(지금은 삭제 경로가 없지만 보관 정리가 생길 때 대비) 그 칸을 건너뛴다 - 500 대신 한 칸 덜 보임(점검 L-4)
        List<SosHistoryItem> items = rows.stream()
                .filter(row -> events.containsKey(row.getLatestId()))
                .map(row -> {
                    SosEvent latest = events.get(row.getLatestId());
                    SosEvent first = events.getOrDefault(row.getFirstId(), latest);
                    return SosHistoryItem.ofGroup(latest, first, row.getPressCount(), names.get(latest.getWardId()))
                            .withCalledGuardianName(called.nameFor(latest));
                })
                .toList();

        return SosHistoryPage.of(new PageImpl<>(items, pageable, total), counts);
    }

    /**
     * 대시보드용 - {@code from} 이후 건수와 가장 최근 1건. 인가는 {@link #getHistory}와 같다.
     * 연결된 피보호자가 없으면 {@code (0, null)}이다(범위 안에서 실제로 센 값이라 "모르는 값"이 아니다).
     *
     * @throws CustomException {@code SOS_NOT_AUTHORIZED} - wardId를 지정했으나 ACTIVE 연결이 아닐 때
     */
    @Transactional(readOnly = true)
    public RecentSos getRecent(String guardianId, String wardId, OffsetDateTime from) {
        List<String> wardIds = resolveVisibleWardIds(guardianId, wardId);
        if (wardIds.isEmpty()) {
            return new RecentSos(0, null);
        }
        long count = sosEventRepository.countByWardIdInAndCreatedAtGreaterThanEqual(wardIds, from);
        List<SosEvent> latest = sosEventRepository
                .findByWardIdInOrderByCreatedAtDesc(wardIds, PageRequest.of(0, 1)).getContent();
        SosHistoryItem item = latest.isEmpty() ? null
                : SosHistoryItem.of(latest.get(0), resolveWardNames(latest).get(latest.get(0).getWardId()));
        return new RecentSos(count, item);
    }

    /** {@link #getRecent} 결과 - {@code count}는 {@code from} 이후, {@code latest}는 기간과 무관한 가장 최근 1건. */
    public record RecentSos(long count, SosHistoryItem latest) {
    }

    /** 인가된 피보호자 범위의 경로별 전체 건수. 집계에 없는 경로는 0건이다(범위 안에서 실제로 센 값). */
    private SosHistoryPage.Counts countByTriggerType(List<String> wardIds) {
        long sosButton = 0;
        long guardianCall = 0;
        for (SosEventRepository.TriggerTypeCount row : sosEventRepository.countByTriggerType(wardIds)) {
            if (row.getTriggerType() == SosTriggerType.GUARDIAN_CALL) {
                guardianCall += row.getTotal();
            } else if (row.getTriggerType() == SosTriggerType.SOS_BUTTON) {
                sosButton += row.getTotal();
            }
        }
        return SosHistoryPage.Counts.of(sosButton, guardianCall);
    }

    /** 인가된 피보호자 범위의 경로별 묶음 수(필터·페이지와 무관). 집계에 없는 경로는 0이다. */
    private SosHistoryPage.Counts countGroupsByTriggerType(List<String> wardIds, int gap) {
        long sosButton = 0;
        long guardianCall = 0;
        for (SosEventRepository.PressGroupCount row
                : sosEventRepository.countPressGroupsByTriggerType(wardIds, "", gap)) {
            if (SosTriggerType.GUARDIAN_CALL.name().equals(row.getTriggerType())) {
                guardianCall += row.getTotal();
            } else if (SosTriggerType.SOS_BUTTON.name().equals(row.getTriggerType())) {
                sosButton += row.getTotal();
            }
        }
        return SosHistoryPage.Counts.of(sosButton, guardianCall);
    }

    /**
     * 조회 대상 피보호자 ID 목록을 인가 검증과 함께 결정한다. 이 메서드를 통과한 목록만 쿼리에 넘긴다.
     */
    private List<String> resolveVisibleWardIds(String guardianId, String wardId) {
        if (!StringUtils.hasText(wardId)) {
            return connectionService.getActiveWardIds(guardianId);
        }
        // 형식이 틀린 값은 DB 조회 없이 거절하고 로그에는 원문 대신 길이만 남긴다 - 줄바꿈 삽입으로 로그 줄 위조 방지(점검 L-1,
        // 복약 MedicationWardIds·챗 대리 상담과 같은 방식)
        if (!WARD_ID_FORMAT.matcher(wardId).matches()) {
            log.warn("[IDOR-ATTEMPT] 형식이 맞지 않는 피보호자 SOS 이력 조회 시도: guardianId={}, wardIdLength={}",
                    guardianId, wardId.length());
            throw new CustomException(ErrorCode.SOS_NOT_AUTHORIZED);
        }
        if (!connectionService.isActiveConnection(guardianId, wardId)) {
            log.warn("[IDOR-ATTEMPT] 연결되지 않은 피보호자 SOS 이력 조회 시도: guardianId={}, wardId={}",
                    guardianId, wardId);
            throw new CustomException(ErrorCode.SOS_NOT_AUTHORIZED);
        }
        return List.of(wardId);
    }

    /**
     * 전화받은 보호자 이름을 한 번에 정한다(2026-10-30). 이름은 그 보호자가 <b>지금</b> 그 피보호자와 ACTIVE 연결일 때만 준다 -
     * 연결을 끊거나 탈퇴한 사람의 이름이 남은 가족의 화면에 계속 남지 않게 한다. 피보호자마다 ACTIVE 보호자 목록을 한 번,
     * 이름을 한 번에 조회한다(N+1 회피).
     */
    private CalledGuardianNames resolveCalledGuardianNames(Collection<SosEvent> events) {
        List<SosEvent> calls = events.stream()
                .filter(event -> event.getCalledGuardianId() != null && event.getWardId() != null)
                .toList();
        if (calls.isEmpty()) {
            return CalledGuardianNames.NONE;
        }
        Map<String, Set<String>> activeGuardiansByWard = calls.stream()
                .map(SosEvent::getWardId)
                .distinct()
                .collect(Collectors.toMap(Function.identity(),
                        wardId -> Set.copyOf(connectionService.getActiveGuardianIds(wardId))));
        Set<String> guardianIds = calls.stream()
                .filter(event -> activeGuardiansByWard.get(event.getWardId()).contains(event.getCalledGuardianId()))
                .map(SosEvent::getCalledGuardianId)
                .collect(Collectors.toSet());
        if (guardianIds.isEmpty()) {
            return CalledGuardianNames.NONE;
        }
        Map<String, String> guardianNames = userRepository.findAllById(guardianIds).stream()
                .filter(user -> StringUtils.hasText(user.getName()))
                .collect(Collectors.toMap(User::getId, User::getName));
        return event -> {
            String guardianId = event.getCalledGuardianId();
            Set<String> active = (event.getWardId() == null) ? null : activeGuardiansByWard.get(event.getWardId());
            return (guardianId == null || active == null || !active.contains(guardianId))
                    ? null : guardianNames.get(guardianId);
        };
    }

    /** 이력 한 건의 전화받은 보호자 이름(보여 줄 수 없으면 null). */
    @FunctionalInterface
    private interface CalledGuardianNames {
        CalledGuardianNames NONE = event -> null;

        String nameFor(SosEvent event);
    }

    /**
     * 이력에 등장하는 피보호자 이름을 한 번에 조회한다(건별 조회로 인한 N+1 회피).
     * 탈퇴로 사라진 사용자는 맵에 없어 {@code null}로 표시된다(관리자 문의 목록의 작성자 처리와 동일).
     * 참고: 보호자 이력 쿼리는 {@code ward_id IN (인가 목록)}이라 탈퇴로 {@code ward_id}가 NULL이 된 익명 이력은
     * 보호자 화면에 나오지 않는다(2026-10-30 확인) - 아래 방어는 이름 조회 중 사용자가 막 탈퇴한 경우 등을 위한 것이다.
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
