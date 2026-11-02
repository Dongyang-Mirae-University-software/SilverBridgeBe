package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.config.CheckinProperties;
import kr.silverbridge.main.domain.checkin.dto.CheckinDailyResponse;
import kr.silverbridge.main.domain.checkin.dto.CheckinDailyResponse.SlotItem;
import kr.silverbridge.main.domain.checkin.dto.CheckinDailyResponse.SlotStatus;
import kr.silverbridge.main.domain.checkin.dto.CheckinWeeklyResponse;
import kr.silverbridge.main.domain.checkin.entity.CheckinResult;
import kr.silverbridge.main.domain.checkin.entity.CheckinSession;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import kr.silverbridge.main.domain.checkin.repository.CheckinNotifyLogRepository;
import kr.silverbridge.main.domain.checkin.repository.CheckinResultRepository;
import kr.silverbridge.main.domain.checkin.repository.CheckinSessionRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 보호자 안부 조회(2026-11-01, 2차). 인가는 ACTIVE 연결만이고, 지금 연결이 맺어진 뒤 기록만 보인다(D-1).
 * 받아쓴 원문은 BE에 없으므로 내려 줄 수도 없다 - 요약 고정 문장·근거(신호·턴 번호)·정서·신호·확인 필요만.
 */
@Service
@RequiredArgsConstructor
public class CheckinReportService {

    static final int MAX_PAST_DAYS = 60;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CheckinProperties properties;
    private final CheckinGuardianAccess access;
    private final CheckinClock clock;
    private final CheckinResultRepository resultRepository;
    private final CheckinSessionRepository sessionRepository;
    private final CheckinNotifyLogRepository logRepository;

    @Transactional(readOnly = true)
    public CheckinDailyResponse daily(String guardianId, String wardId, LocalDate date) {
        requireActive();
        access.require(guardianId, wardId, "daily");
        OffsetDateTime now = clock.now();
        LocalDate day = validDate(date, now.toLocalDate());

        Map<CheckinSlot, CheckinResult> results = new EnumMap<>(CheckinSlot.class);
        resultRepository.findByWardIdAndCheckDateBetweenOrderByCheckDateAscSlotAsc(wardId, day, day)
                .forEach(r -> results.put(r.getSlot(), r));
        Set<CheckinSlot> inProgress = new HashSet<>();
        for (CheckinSession s : sessionRepository.findByWardIdAndCheckDate(wardId, day)) {
            if (s.isInProgress()) {
                inProgress.add(s.getSlot());
            }
        }
        Set<String> prompted = new HashSet<>(logRepository.promptedSlots(wardId, day));

        List<SlotItem> items = new ArrayList<>();
        boolean dayAttention = false;
        for (CheckinSlot slot : CheckinSlot.values()) {
            OffsetDateTime slotStart = day.atTime(slot.time()).atZone(KST).toOffsetDateTime();
            CheckinResult result = results.get(slot);
            OffsetDateTime basis = result != null ? result.getCreatedAt() : slotStart;
            if (!access.visible(guardianId, wardId, basis)) {
                items.add(SlotItem.empty(slot, SlotStatus.BEFORE_CONNECTION));
                continue;
            }
            if (result != null) {
                boolean risk = result.isRiskEmergency() || result.isRiskSelfHarm();
                dayAttention |= result.isAttention() || risk;
                items.add(new SlotItem(slot, SlotStatus.DONE, result.getEmotion(), result.getSelfReport(),
                        new CheckinDailyResponse.Signals(result.getSleep(), result.getMeal(), result.getPain(),
                                result.getMood(), result.getLonely()),
                        risk, result.isAttention(), result.getAttentionReasons(), result.getSummary(),
                        result.getEvidence()));
                continue;
            }
            items.add(SlotItem.empty(slot, statusWithoutResult(slot, day, now, inProgress.contains(slot),
                    prompted.contains(slot.value()))));
        }
        return new CheckinDailyResponse(wardId, day, dayAttention, items);
    }

    @Transactional(readOnly = true)
    public CheckinWeeklyResponse weekly(String guardianId, String wardId, LocalDate endDate) {
        requireActive();
        access.require(guardianId, wardId, "weekly");
        OffsetDateTime now = clock.now();
        LocalDate to = validDate(endDate, now.toLocalDate());
        LocalDate from = to.minusDays(6);
        Map<LocalDate, List<CheckinResult>> byDay = new java.util.TreeMap<>();
        for (CheckinResult r : resultRepository.findByWardIdAndCheckDateBetweenOrderByCheckDateAscSlotAsc(wardId, from, to)) {
            if (access.visible(guardianId, wardId, r.getCreatedAt())) {
                byDay.computeIfAbsent(r.getCheckDate(), d -> new ArrayList<>()).add(r);
            }
        }
        List<CheckinWeeklyResponse.Day> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            List<CheckinResult> list = byDay.getOrDefault(d, List.of());
            Set<String> done = new HashSet<>();
            list.forEach(r -> done.add(r.getSlot().value()));
            int missed = 0;
            for (String slotValue : logRepository.promptedSlots(wardId, d)) {
                CheckinSlot slot = CheckinSlot.fromValue(slotValue).orElse(null);
                if (slot == null || done.contains(slotValue)) {
                    continue;
                }
                OffsetDateTime slotStart = d.atTime(slot.time()).atZone(KST).toOffsetDateTime();
                if (windowClosed(slot, d, now) && access.visible(guardianId, wardId, slotStart)) {
                    missed++;
                }
            }
            days.add(new CheckinWeeklyResponse.Day(d, list.size(), missed,
                    (int) list.stream().filter(CheckinResult::isAttention).count(),
                    list.stream().anyMatch(r -> r.isRiskEmergency() || r.isRiskSelfHarm()),
                    list.stream().map(r -> new CheckinWeeklyResponse.SlotEmotion(r.getSlot(), r.getEmotion())).toList()));
        }
        return new CheckinWeeklyResponse(wardId, from, to, days);
    }

    private SlotStatus statusWithoutResult(CheckinSlot slot, LocalDate day, OffsetDateTime now, boolean inProgress,
                                           boolean prompted) {
        if (inProgress) {
            return SlotStatus.IN_PROGRESS;
        }
        LocalDateTime start = day.atTime(slot.time());
        if (now.toLocalDateTime().isBefore(start)) {
            return SlotStatus.UPCOMING;
        }
        if (!windowClosed(slot, day, now)) {
            return SlotStatus.OPEN;
        }
        return prompted ? SlotStatus.MISSED : SlotStatus.NO_RECORD;
    }

    private boolean windowClosed(CheckinSlot slot, LocalDate day, OffsetDateTime now) {
        return !now.toLocalDateTime().isBefore(day.atTime(slot.time()).plus(properties.getResponseWindow()));
    }

    /** null = 오늘. 미래·60일보다 이전은 400. */
    private static LocalDate validDate(LocalDate date, LocalDate today) {
        LocalDate day = date == null ? today : date;
        if (day.isAfter(today) || day.isBefore(today.minusDays(MAX_PAST_DAYS))) {
            throw new CustomException(ErrorCode.CHECKIN_INVALID_DATE);
        }
        return day;
    }

    private void requireActive() {
        if (!properties.isActive()) {
            throw new CustomException(ErrorCode.CHECKIN_DISABLED);
        }
    }
}
