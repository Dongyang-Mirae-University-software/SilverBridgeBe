package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.config.CheckinProperties;
import kr.silverbridge.main.domain.checkin.dto.CheckinQuestionsResponse;
import kr.silverbridge.main.domain.checkin.dto.CheckinQuestionsUpdateRequest;
import kr.silverbridge.main.domain.checkin.dto.CheckinQuestionsUpdateRequest.Item;
import kr.silverbridge.main.domain.checkin.entity.CheckinQuestion;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import kr.silverbridge.main.domain.checkin.repository.CheckinQuestionRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CheckinQuestionServiceTest {

    private static final String GUARDIAN = "gA1b2C";
    private static final String WARD = "wA1b2C";

    private final CheckinProperties properties = new CheckinProperties();
    private final CheckinQuestionRepository repository = mock(CheckinQuestionRepository.class);
    private final ConnectionService connectionService = mock(ConnectionService.class);
    private CheckinQuestionService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setAiBaseUrl("http://127.0.0.1:1");
        properties.setApiKey("k");
        service = new CheckinQuestionService(properties, repository, new CheckinGuardianAccess(connectionService));
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
    }

    @Test
    @DisplayName("저장값이 없으면 다섯 시각 모두 기본 질문(custom=false)")
    void defaults() {
        CheckinQuestionsResponse response = service.get(GUARDIAN, WARD);

        assertThat(response.questions()).hasSize(5)
                .allSatisfy(item -> assertThat(item.custom()).isFalse())
                .extracting(CheckinQuestionsResponse.Item::slot).containsExactly(CheckinSlot.values());
    }

    @Test
    @DisplayName("ACTIVE 연결이 아니면 403 CHECKIN_NOT_AUTHORIZED이고 저장소를 보지 않는다")
    void notConnected() {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(false);
        assertCode(() -> service.get(GUARDIAN, WARD), ErrorCode.CHECKIN_NOT_AUTHORIZED);
        assertCode(() -> service.update(GUARDIAN, WARD, request(new Item(CheckinSlot.AT_09, "안녕하세요"))),
                ErrorCode.CHECKIN_NOT_AUTHORIZED);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("형식이 틀린 피보호자 ID는 연결 조회도 하지 않고 403")
    void malformedWardId() {
        assertCode(() -> service.get(GUARDIAN, "x\nINJECT"), ErrorCode.CHECKIN_NOT_AUTHORIZED);
        verify(connectionService, never()).isActiveConnection(anyString(), anyString());
    }

    @Test
    @DisplayName("꺼진 서버면 503 CHECKIN_DISABLED")
    void disabled() {
        properties.setEnabled(false);
        assertCode(() -> service.get(GUARDIAN, WARD), ErrorCode.CHECKIN_DISABLED);
        verifyNoInteractions(connectionService);
    }

    @Test
    @DisplayName("새 질문은 정리해서 저장하고, 고친 보호자를 남긴다")
    void savesNew() {
        when(repository.findByWardIdAndSlot(WARD, CheckinSlot.AT_12)).thenReturn(Optional.empty());

        service.update(GUARDIAN, WARD, request(new Item(CheckinSlot.AT_12, "  점심은   드셨어요? ")));

        verify(repository).save(org.mockito.ArgumentMatchers.argThat(q ->
                q.getQuestion().equals("점심은 드셨어요?") && GUARDIAN.equals(q.getUpdatedBy())
                        && q.getSlot() == CheckinSlot.AT_12));
    }

    @Test
    @DisplayName("빈 문자열이면 저장된 질문을 지워 기본 질문으로 돌린다")
    void emptyResets() {
        CheckinQuestion saved = CheckinQuestion.of(WARD, CheckinSlot.AT_09, "옛 질문", GUARDIAN);
        when(repository.findByWardIdAndSlot(WARD, CheckinSlot.AT_09)).thenReturn(Optional.of(saved));

        service.update(GUARDIAN, WARD, request(new Item(CheckinSlot.AT_09, "")));

        verify(repository).delete(saved);
    }

    @Test
    @DisplayName("200자를 넘거나, 보이는 글자가 없거나, 같은 시각이 두 번이면 400")
    void invalid() {
        assertCode(() -> service.update(GUARDIAN, WARD, request(new Item(CheckinSlot.AT_09, "가".repeat(201)))),
                ErrorCode.CHECKIN_INVALID_QUESTION);
        assertCode(() -> service.update(GUARDIAN, WARD, request(new Item(CheckinSlot.AT_09, "ㅤㅤ"))),
                ErrorCode.CHECKIN_INVALID_QUESTION);
        assertCode(() -> service.update(GUARDIAN, WARD,
                        request(new Item(CheckinSlot.AT_09, "a"), new Item(CheckinSlot.AT_09, "b"))),
                ErrorCode.CHECKIN_INVALID_QUESTION);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("200자 정확히는 허용")
    void exactly200() {
        when(repository.findByWardIdAndSlot(WARD, CheckinSlot.AT_09)).thenReturn(Optional.empty());
        service.update(GUARDIAN, WARD, request(new Item(CheckinSlot.AT_09, "가".repeat(200))));
        verify(repository).save(any());
    }

    @Test
    @DisplayName("대화 시작 질문: 저장값이 있으면 그것, 없으면 기본 질문")
    void questionFor() {
        when(repository.findByWardIdAndSlot(WARD, CheckinSlot.AT_21))
                .thenReturn(Optional.of(CheckinQuestion.of(WARD, CheckinSlot.AT_21, "주무시기 전 기분은요?", GUARDIAN)));
        assertThat(service.questionFor(WARD, CheckinSlot.AT_21)).isEqualTo("주무시기 전 기분은요?");
        assertThat(service.questionFor(WARD, CheckinSlot.AT_09)).isEqualTo(CheckinSlot.AT_09.defaultQuestion());
    }

    private static CheckinQuestionsUpdateRequest request(Item... items) {
        return new CheckinQuestionsUpdateRequest(List.of(items));
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(code);
    }
}
