package kr.silverbridge.main.domain.sos.service;

import kr.silverbridge.main.domain.sos.dto.SosResponse;
import kr.silverbridge.main.domain.sos.entity.SosEvent;
import kr.silverbridge.main.domain.sos.entity.SosTriggerType;
import kr.silverbridge.main.domain.sos.event.SosTriggeredEvent;
import kr.silverbridge.main.domain.sos.repository.SosEventRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SosService 단위 테스트.
 *
 * SOS 발생 시 ① 이력(sos_events) 저장과 ② 커밋 후 알림 발송용 {@link SosTriggeredEvent} 발행을 검증한다.
 * 실제 알림 발송은 AFTER_COMMIT 리스너의 책임이므로 여기서는 이력·이벤트만 본다.
 */
@ExtendWith(MockitoExtension.class)
class SosServiceTest {

    @Mock private SosEventRepository sosEventRepository;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private SosService sosService;

    private static final String WARD_ID = "WD0001";
    private static final String WARD_NAME = "김순자";

    @Test
    @DisplayName("SOS 발생 → 이력(wardId) 저장 + SosTriggeredEvent(이름·이력ID 포함) 발행 + 응답 반환")
    void trigger_저장_이벤트발행_응답() {
        User ward = User.builder().id(WARD_ID).name(WARD_NAME).role(Role.WARD).build();
        when(userRepository.findById(WARD_ID)).thenReturn(Optional.of(ward));

        OffsetDateTime now = OffsetDateTime.now();
        SosEvent saved = mock(SosEvent.class);
        when(saved.getId()).thenReturn(42L);
        when(saved.getCreatedAt()).thenReturn(now);
        when(sosEventRepository.save(any(SosEvent.class))).thenReturn(saved);

        SosResponse res = sosService.trigger(WARD_ID, null, null);

        // 이력 저장 — wardId 보존
        ArgumentCaptor<SosEvent> saveCaptor = ArgumentCaptor.forClass(SosEvent.class);
        verify(sosEventRepository).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().getWardId()).isEqualTo(WARD_ID);

        // 이벤트 발행 — 커밋 후 알림 발송용. 피보호자 이름·이력 ID 전달
        ArgumentCaptor<SosTriggeredEvent> pubCaptor = ArgumentCaptor.forClass(SosTriggeredEvent.class);
        verify(eventPublisher).publishEvent(pubCaptor.capture());
        assertThat(pubCaptor.getValue().wardId()).isEqualTo(WARD_ID);
        assertThat(pubCaptor.getValue().sosEventId()).isEqualTo(42L);
        assertThat(pubCaptor.getValue().wardName()).isEqualTo(WARD_NAME);

        // 응답
        assertThat(res.sosEventId()).isEqualTo(42L);
        assertThat(res.triggeredAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("피보호자 이름이 비어 있으면 폴백('보호 대상자')으로 이벤트 발행 — \"null님이...\" 방지")
    void trigger_이름없음_폴백() {
        User ward = User.builder().id(WARD_ID).name(null).role(Role.WARD).build();
        when(userRepository.findById(WARD_ID)).thenReturn(Optional.of(ward));

        SosEvent saved = mock(SosEvent.class);
        when(saved.getId()).thenReturn(42L);
        when(saved.getCreatedAt()).thenReturn(OffsetDateTime.now());
        when(sosEventRepository.save(any(SosEvent.class))).thenReturn(saved);

        sosService.trigger(WARD_ID, null, null);

        ArgumentCaptor<SosTriggeredEvent> pubCaptor = ArgumentCaptor.forClass(SosTriggeredEvent.class);
        verify(eventPublisher).publishEvent(pubCaptor.capture());
        assertThat(pubCaptor.getValue().wardName()).isEqualTo("보호 대상자");
    }

    @Test
    @DisplayName("존재하지 않는 사용자 → USER_NOT_FOUND, 이력 미저장·이벤트 미발행")
    void trigger_사용자없음() {
        when(userRepository.findById(WARD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sosService.trigger(WARD_ID, null, null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);

        verifyNoInteractions(sosEventRepository);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("위치를 함께 보내면 이력에 그대로 저장 — 서버는 위치를 추정하지 않는다")
    void trigger_위치저장() {
        User ward = User.builder().id(WARD_ID).name(WARD_NAME).role(Role.WARD).build();
        when(userRepository.findById(WARD_ID)).thenReturn(Optional.of(ward));
        SosEvent saved = mock(SosEvent.class);
        when(saved.getId()).thenReturn(42L);
        when(saved.getCreatedAt()).thenReturn(OffsetDateTime.now());
        when(sosEventRepository.save(any(SosEvent.class))).thenReturn(saved);

        sosService.trigger(WARD_ID, "자택 거실", null);

        ArgumentCaptor<SosEvent> saveCaptor = ArgumentCaptor.forClass(SosEvent.class);
        verify(sosEventRepository).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().getLocation()).isEqualTo("자택 거실");
    }

    @Test
    @DisplayName("위치 미전송(바디 없음)·공백만 전송 → null로 저장(위치 미상). 기존 호출 방식 그대로 동작")
    void trigger_위치없음_null() {
        User ward = User.builder().id(WARD_ID).name(WARD_NAME).role(Role.WARD).build();
        when(userRepository.findById(WARD_ID)).thenReturn(Optional.of(ward));
        SosEvent saved = mock(SosEvent.class);
        when(saved.getId()).thenReturn(42L);
        when(saved.getCreatedAt()).thenReturn(OffsetDateTime.now());
        when(sosEventRepository.save(any(SosEvent.class))).thenReturn(saved);

        sosService.trigger(WARD_ID, "   ", null);

        ArgumentCaptor<SosEvent> saveCaptor = ArgumentCaptor.forClass(SosEvent.class);
        verify(sosEventRepository).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().getLocation()).isNull();
    }

    @Test
    @DisplayName("발생 경로 미전송 → SOS_BUTTON으로 기록 (바디 없는 기존 호출 하위호환)")
    void trigger_경로없음_기본값() {
        stubSavedEvent();

        sosService.trigger(WARD_ID, null, null);

        ArgumentCaptor<SosEvent> saveCaptor = ArgumentCaptor.forClass(SosEvent.class);
        verify(sosEventRepository).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().getTriggerType()).isEqualTo(SosTriggerType.SOS_BUTTON);
    }

    @Test
    @DisplayName("보호자 직접 전화(GUARDIAN_CALL)도 이력에 남고 알림 이벤트가 동일하게 발행된다")
    void trigger_보호자전화_이력과알림() {
        stubSavedEvent();

        sosService.trigger(WARD_ID, null, SosTriggerType.GUARDIAN_CALL);

        ArgumentCaptor<SosEvent> saveCaptor = ArgumentCaptor.forClass(SosEvent.class);
        verify(sosEventRepository).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().getTriggerType()).isEqualTo(SosTriggerType.GUARDIAN_CALL);

        // 발생 경로는 이력 표시용일 뿐 알림을 가르지 않는다 - 전화받은 보호자 외 나머지도 알아야 한다
        verify(eventPublisher).publishEvent(any(SosTriggeredEvent.class));
    }

    /** 세 테스트가 공유하는 저장 결과 스텁 - 반환 mock은 응답 조립에만 쓰인다. */
    private void stubSavedEvent() {
        User ward = User.builder().id(WARD_ID).name(WARD_NAME).role(Role.WARD).build();
        when(userRepository.findById(WARD_ID)).thenReturn(Optional.of(ward));
        SosEvent saved = mock(SosEvent.class);
        when(saved.getId()).thenReturn(42L);
        when(saved.getCreatedAt()).thenReturn(OffsetDateTime.now());
        when(sosEventRepository.save(any(SosEvent.class))).thenReturn(saved);
    }
}
