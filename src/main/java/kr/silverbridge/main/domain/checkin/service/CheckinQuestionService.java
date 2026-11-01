package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.config.CheckinProperties;
import kr.silverbridge.main.domain.checkin.dto.CheckinQuestionsResponse;
import kr.silverbridge.main.domain.checkin.dto.CheckinQuestionsUpdateRequest;
import kr.silverbridge.main.domain.checkin.entity.CheckinQuestion;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import kr.silverbridge.main.domain.checkin.repository.CheckinQuestionRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.validation.TextSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 보호자가 정하는 시각별 안부 질문. 인가는 ACTIVE 연결만({@code isActiveConnection} - {@code getMyWards}는 PENDING이 섞여 금지),
 * 위반은 403 {@code CHECKIN_NOT_AUTHORIZED} + {@code [IDOR-ATTEMPT]}. 질문은 피보호자 단위라 같은 피보호자를 보는 보호자끼리 공유한다.
 * 질문 본문은 로그에 남기지 않는다(시각만).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckinQuestionService {

    static final int MAX_QUESTION = 200;

    private final CheckinProperties properties;
    private final CheckinQuestionRepository repository;
    private final CheckinGuardianAccess access;

    @Transactional(readOnly = true)
    public CheckinQuestionsResponse get(String guardianId, String wardId) {
        requireActive();
        access.require(guardianId, wardId, "questions-get");
        return toResponse(wardId);
    }

    @Transactional
    public CheckinQuestionsResponse update(String guardianId, String wardId, CheckinQuestionsUpdateRequest request) {
        requireActive();
        access.require(guardianId, wardId, "questions-update");
        Set<CheckinSlot> seen = EnumSet.noneOf(CheckinSlot.class);
        List<CheckinSlot> changed = new ArrayList<>();
        for (CheckinQuestionsUpdateRequest.Item item : request.questions()) {
            if (item == null || item.slot() == null || item.question() == null || !seen.add(item.slot())) {
                throw new CustomException(ErrorCode.CHECKIN_INVALID_QUESTION);
            }
        }
        for (CheckinQuestionsUpdateRequest.Item item : request.questions()) {
            String question = TextSanitizer.sanitize(item.question());
            if (question == null || question.isEmpty()) {
                // 빈 문자열 = 기본 질문으로 되돌리기
                repository.findByWardIdAndSlot(wardId, item.slot()).ifPresent(repository::delete);
            } else {
                if (TextSanitizer.codePointLength(question) > MAX_QUESTION || !TextSanitizer.hasVisibleChar(question)) {
                    throw new CustomException(ErrorCode.CHECKIN_INVALID_QUESTION);
                }
                repository.findByWardIdAndSlot(wardId, item.slot()).ifPresentOrElse(
                        saved -> saved.change(question, guardianId),
                        () -> repository.save(CheckinQuestion.of(wardId, item.slot(), question, guardianId)));
            }
            changed.add(item.slot());
        }
        repository.flush();
        log.info("[CHECKIN] 안부 질문 변경: guardianId={}, wardId={}, slots={}", guardianId, wardId,
                changed.stream().map(CheckinSlot::value).toList());
        return toResponse(wardId);
    }

    /** 대화 시작 때 보낼 질문 - 저장값이 없으면 기본 질문. */
    @Transactional(readOnly = true)
    public String questionFor(String wardId, CheckinSlot slot) {
        return repository.findByWardIdAndSlot(wardId, slot).map(CheckinQuestion::getQuestion)
                .orElse(slot.defaultQuestion());
    }

    private CheckinQuestionsResponse toResponse(String wardId) {
        Map<CheckinSlot, String> custom = new EnumMap<>(CheckinSlot.class);
        repository.findByWardId(wardId).forEach(q -> custom.put(q.getSlot(), q.getQuestion()));
        List<CheckinQuestionsResponse.Item> items = new ArrayList<>();
        for (CheckinSlot slot : CheckinSlot.values()) {
            String saved = custom.get(slot);
            items.add(new CheckinQuestionsResponse.Item(slot, saved != null ? saved : slot.defaultQuestion(),
                    saved != null));
        }
        return new CheckinQuestionsResponse(items);
    }

    private void requireActive() {
        if (!properties.isActive()) {
            throw new CustomException(ErrorCode.CHECKIN_DISABLED);
        }
    }
}
