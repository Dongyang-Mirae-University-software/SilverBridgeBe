package kr.silverbridge.main.domain.checkin;

import kr.silverbridge.main.domain.checkin.entity.CheckinEmotion;
import kr.silverbridge.main.domain.checkin.entity.CheckinEndReason;
import kr.silverbridge.main.domain.checkin.entity.CheckinResult;
import kr.silverbridge.main.domain.checkin.entity.CheckinSession;
import kr.silverbridge.main.domain.checkin.entity.CheckinSessionStatus;
import kr.silverbridge.main.domain.checkin.entity.CheckinSetting;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import kr.silverbridge.main.domain.checkin.repository.CheckinNotifyLogRepository;
import kr.silverbridge.main.domain.checkin.repository.CheckinQuestionRepository;
import kr.silverbridge.main.domain.checkin.repository.CheckinResultRepository;
import kr.silverbridge.main.domain.checkin.repository.CheckinSessionRepository;
import kr.silverbridge.main.domain.checkin.repository.CheckinSettingRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 안부 말벗 V65·V66을 실제 PostgreSQL로 검증한다: 선점 쿼리(ON CONFLICT)·진행 중 대화 부분 UNIQUE·결과 JSONB·
 * 신호 CHECK·피보호자 탈퇴 CASCADE.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CheckinRepositoryIntegrationTest extends PostgresIntegrationTest {

    private static final String WARD = "CKW001";
    private static final String GUARDIAN = "CKG001";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 1);
    private static final OffsetDateTime AT = OffsetDateTime.of(DAY.atTime(9, 5), ZoneOffset.ofHours(9));

    @Autowired private UserRepository userRepository;
    @Autowired private CheckinSessionRepository sessionRepository;
    @Autowired private CheckinResultRepository resultRepository;
    @Autowired private CheckinSettingRepository settingRepository;
    @Autowired private CheckinNotifyLogRepository logRepository;
    @Autowired private CheckinQuestionRepository questionRepository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user(WARD, "피보호자", Role.WARD));
        userRepository.save(TestData.user(GUARDIAN, "보호자", Role.GUARDIAN));
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAllById(List.of(WARD, GUARDIAN));
    }

    @Test
    @DisplayName("선점: 같은 (피보호자, 날짜, 시각)·(보호자, 피보호자, 날짜, 시각, 종류)는 처음 한 번만 1, 이후 0")
    void claims() {
        assertThat(logRepository.claimPrompt(WARD, DAY, "09:00")).isEqualTo(1);
        assertThat(logRepository.claimPrompt(WARD, DAY, "09:00")).isZero();
        assertThat(logRepository.claimPrompt(WARD, DAY, "12:00")).isEqualTo(1);
        assertThat(logRepository.promptedWards(DAY, "09:00")).containsExactly(WARD);
        assertThat(logRepository.promptedSlots(WARD, DAY)).containsExactlyInAnyOrder("09:00", "12:00");

        assertThat(logRepository.claimGuardianAlert(GUARDIAN, WARD, DAY, "09:00", "MISSED")).isEqualTo(1);
        assertThat(logRepository.claimGuardianAlert(GUARDIAN, WARD, DAY, "09:00", "MISSED")).isZero();
        assertThat(logRepository.alertedOnDate(GUARDIAN, WARD, DAY, "MISSED")).isTrue();
        assertThat(logRepository.alertedOnDate(GUARDIAN, WARD, DAY, "RISK")).isFalse();
    }

    @Test
    @DisplayName("같은 시각에 진행 중인 대화는 하나뿐(부분 UNIQUE) - 닫힌 대화가 있으면 새로 만들 수 있다")
    void oneInProgressPerSlot() {
        sessionRepository.saveAndFlush(session("ck_aaaaaaaaaaaaaaaaaaaaaaaa"));
        assertThatThrownBy(() -> sessionRepository.saveAndFlush(session("ck_bbbbbbbbbbbbbbbbbbbbbbbb")))
                .isInstanceOf(DataIntegrityViolationException.class);

        CheckinSession first = sessionRepository.findBySessionId("ck_aaaaaaaaaaaaaaaaaaaaaaaa").orElseThrow();
        first.expire(AT);
        sessionRepository.saveAndFlush(first);
        sessionRepository.saveAndFlush(session("ck_cccccccccccccccccccccccc"));
        assertThat(sessionRepository.findByWardIdAndCheckDateAndSlotAndStatus(WARD, DAY, CheckinSlot.AT_09,
                CheckinSessionStatus.IN_PROGRESS)).isPresent();
    }

    @Test
    @DisplayName("결과의 근거·확인 필요 근거(JSONB) 왕복, 기간 조회, 피보호자 탈퇴 시 CASCADE")
    void resultRoundTripAndCascade() {
        CheckinSession s = sessionRepository.saveAndFlush(session("ck_dddddddddddddddddddddddd"));
        resultRepository.saveAndFlush(CheckinResult.builder().sessionPk(s.getId()).wardId(WARD).checkDate(DAY)
                .slot(CheckinSlot.AT_09).emotion(CheckinEmotion.LONELY).selfReport(CheckinEmotion.WORRIED)
                .sleep("BAD").lonely("YES").summary("잠을 설치셨다고 하셨어요.")
                .evidence(List.of(new CheckinResult.Evidence("sleep", 1))).turns(2).endReason(CheckinEndReason.USER_END)
                .avgAnswerSec(1.5).createdAt(AT).attention(true)
                .attentionReasons(List.of("SLEEP_UNUSUAL", "LONELY_UNUSUAL")).build());
        CheckinSetting setting = CheckinSetting.of(WARD, false);
        setting.changePromptEnabled(true, GUARDIAN);
        settingRepository.saveAndFlush(setting);

        CheckinResult loaded = resultRepository
                .findByWardIdAndCheckDateBetweenOrderByCheckDateAscSlotAsc(WARD, DAY.minusDays(1), DAY).get(0);
        assertThat(loaded.getEvidence()).containsExactly(new CheckinResult.Evidence("sleep", 1));
        assertThat(loaded.getAttentionReasons()).containsExactly("SLEEP_UNUSUAL", "LONELY_UNUSUAL");
        assertThat(settingRepository.findByPromptEnabledTrue()).extracting(CheckinSetting::getWardId).contains(WARD);

        // 보호자 탈퇴: 설정의 prompt_changed_by만 비워지고 설정은 남는다
        userRepository.deleteById(GUARDIAN);
        assertThat(settingRepository.findByWardId(WARD)).hasValueSatisfying(x -> assertThat(x.getPromptChangedBy()).isNull());

        userRepository.deleteById(WARD);
        assertThat(resultRepository.count()).isZero();
        assertThat(sessionRepository.count()).isZero();
        assertThat(settingRepository.findByWardId(WARD)).isEmpty();
    }

    @Test
    @DisplayName("신호 CHECK: 허용되지 않은 값은 DB가 거부한다")
    void signalCheck() {
        CheckinSession s = sessionRepository.saveAndFlush(session("ck_eeeeeeeeeeeeeeeeeeeeeeee"));
        assertThatThrownBy(() -> resultRepository.saveAndFlush(CheckinResult.builder().sessionPk(s.getId()).wardId(WARD)
                .checkDate(DAY).slot(CheckinSlot.AT_09).emotion(CheckinEmotion.CALM).sleep("나쁨")
                .endReason(CheckinEndReason.USER_END).createdAt(AT).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("질문 upsert(점검 L-4): 처음엔 넣고 다음엔 같은 행을 고친다 - UNIQUE 위반 없음")
    void questionUpsert() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.executeWithoutResult(st -> questionRepository.upsert(WARD, "09:00", "첫 질문", GUARDIAN));
        tx.executeWithoutResult(st -> questionRepository.upsert(WARD, "09:00", "고친 질문", GUARDIAN));

        assertThat(questionRepository.findByWardId(WARD)).singleElement()
                .satisfies(q -> assertThat(q.getQuestion()).isEqualTo("고친 질문"));
    }

    @Test
    @DisplayName("연결 종료(점검 L-1): 그 보호자가 켠 안부 알림만 꺼진다")
    void disablePromptEnabledBy() {
        CheckinSetting setting = CheckinSetting.of(WARD, false);
        setting.changePromptEnabled(true, GUARDIAN);
        settingRepository.saveAndFlush(setting);

        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        Integer other = tx.execute(st -> settingRepository.disablePromptEnabledBy(WARD, "OTHER1"));
        Integer mine = tx.execute(st -> settingRepository.disablePromptEnabledBy(WARD, GUARDIAN));
        assertThat(other).isZero();
        assertThat(mine).isEqualTo(1);
        assertThat(settingRepository.findByWardId(WARD)).hasValueSatisfying(x -> {
            assertThat(x.isPromptEnabled()).isFalse();
            assertThat(x.getPromptChangedBy()).isNull();
        });
    }

    @Test
    @DisplayName("보관 정리(점검 M-4): 기준 날짜 전 발송 기록·대화(결과 CASCADE)만 지운다")
    void retentionDeletes() {
        logRepository.claimPrompt(WARD, DAY.minusDays(100), "09:00");
        logRepository.claimPrompt(WARD, DAY, "09:00");
        logRepository.claimGuardianAlert(GUARDIAN, WARD, DAY.minusDays(100), "09:00", "MISSED");
        CheckinSession old = sessionRepository.saveAndFlush(CheckinSession.start("ck_oldoldoldoldoldoldoldold", WARD,
                DAY.minusDays(400), CheckinSlot.AT_09, "q", AT.minusDays(400)));
        resultRepository.saveAndFlush(CheckinResult.builder().sessionPk(old.getId()).wardId(WARD)
                .checkDate(DAY.minusDays(400)).slot(CheckinSlot.AT_09).emotion(CheckinEmotion.CALM)
                .endReason(CheckinEndReason.USER_END).createdAt(AT.minusDays(400)).build());
        sessionRepository.saveAndFlush(session("ck_newnewnewnewnewnewnewnew"));

        assertThat(logRepository.deletePromptLogsBefore(DAY.minusDays(90))).isEqualTo(1);
        assertThat(logRepository.deleteGuardianAlertLogsBefore(DAY.minusDays(90))).isEqualTo(1);
        assertThat(logRepository.deleteSessionsBefore(DAY.minusDays(365))).isEqualTo(1);
        assertThat(resultRepository.count()).isZero();
        assertThat(sessionRepository.count()).isEqualTo(1);
        assertThat(logRepository.promptedSlots(WARD, DAY)).containsExactly("09:00");
    }

    private static CheckinSession session(String id) {
        return CheckinSession.start(id, WARD, DAY, CheckinSlot.AT_09, "질문", AT);
    }
}
