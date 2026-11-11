package kr.silverbridge.main.domain.chat.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.chat.client.AiChatClient;
import kr.silverbridge.main.domain.chat.config.ChatRelayProperties;
import kr.silverbridge.main.domain.chat.dto.ChatRelayRequest;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDate;
import kr.silverbridge.main.global.enums.Gender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 대리 상담: wardId 는 BE 가 ACTIVE 연결로 검증하고, 이름·전화는 DB 값으로만 AI 에 나간다(2026-10-20). */
@ExtendWith(MockitoExtension.class)
class ChatRelayWardContextTest {

    private static final String GUARDIAN = "GRD001";
    private static final String WARD = "WRD001";

    @Mock
    private AiChatClient aiChatClient;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private ConnectionService connectionService;
    @Mock
    private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChatRelayService service;

    @BeforeEach
    void setUp() {
        ChatRelayProperties properties = new ChatRelayProperties();
        service = new ChatRelayService(aiChatClient, new ChatSlots(properties), rateLimitService, properties,
                objectMapper, connectionService, userRepository);
    }

    private static ChatRelayRequest withContext(Map<String, Object> context) {
        return new ChatRelayRequest("병원 예약해줘", "s1", null, context, null);
    }

    private static Map<String, Object> forged(String wardId) {
        Map<String, Object> context = new HashMap<>();
        context.put("wardId", wardId);
        context.put("wardName", "위조이름");
        context.put("wardPhone", "010-9999-9999");
        context.put("name", "보호자");
        return context;
    }

    private JsonNode sentBody() throws Exception {
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(aiChatClient).send(body.capture());
        return objectMapper.readTree(body.getValue());
    }

    private User ward() {
        return User.builder().id(WARD).name("김영희").phone("010-1234-5678").build();
    }

    private User guardian() {
        return User.builder().id(GUARDIAN).name("박보호").phone("010-2222-3333").build();
    }

    @Test
    @DisplayName("연결되지 않은 wardId → 403 CHAT_WARD_NOT_CONNECTED, AI 호출 없음")
    void 연결_안_된_wardId는_거절() {
        when(connectionService.isActiveConnection(GUARDIAN, "VICTIM")).thenReturn(false);

        assertThatThrownBy(() -> service.sendAsGuardian(GUARDIAN, withContext(forged("VICTIM"))))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_WARD_NOT_CONNECTED));

        verifyNoInteractions(aiChatClient);
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("ACTIVE 연결이 아닌 경우(PENDING 포함)는 isActiveConnection=false 라 같은 403")
    void PENDING_연결도_거절() {
        // isActiveConnection 은 ACTIVE 만 true 를 돌려준다 - PENDING 은 false
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(false);

        assertThatThrownBy(() -> service.sendAsGuardian(GUARDIAN, withContext(forged(WARD))))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_WARD_NOT_CONNECTED));
        verifyNoInteractions(aiChatClient);
    }

    @Test
    @DisplayName("ACTIVE 연결이면 wardName·wardPhone 은 클라이언트 값 대신 DB 값으로 나간다")
    void ACTIVE면_DB_값으로_덮어쓴다() throws Exception {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        when(userRepository.findById(WARD)).thenReturn(Optional.of(ward()));
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));

        service.sendAsGuardian(GUARDIAN, withContext(forged(WARD)));

        JsonNode body = sentBody();
        JsonNode context = body.path("context");
        assertThat(context.path("wardId").asText()).isEqualTo(WARD);
        assertThat(context.path("wardName").asText()).isEqualTo("김영희");
        assertThat(context.path("wardPhone").asText()).isEqualTo("010-1234-5678");
        assertThat(context.toString()).doesNotContain("위조이름").doesNotContain("9999");
        assertThat(body.path("userId").asText()).isEqualTo(GUARDIAN);
    }

    @Test
    @DisplayName("wardId 가 문자열이 아니면 400")
    void 숫자_wardId는_400() {
        Map<String, Object> numeric = new HashMap<>();
        numeric.put("wardId", 123);

        assertThatThrownBy(() -> service.sendAsGuardian(GUARDIAN, withContext(numeric)))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_INVALID_REQUEST));
        verifyNoInteractions(aiChatClient);
    }

    @Test
    @DisplayName("빈 wardId 는 본인 상담 - 대리 키는 모두 지우고 name·phone 은 보호자 본인의 DB 값이다")
    void 빈_wardId는_대리_키를_지운다() throws Exception {
        when(userRepository.findById(GUARDIAN)).thenReturn(Optional.of(guardian()));
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));

        service.sendAsGuardian(GUARDIAN, withContext(forged("  ")));

        JsonNode context = sentBody().path("context");
        assertThat(context.has("wardId")).isFalse();
        assertThat(context.has("wardName")).isFalse();
        assertThat(context.has("wardPhone")).isFalse();
        assertThat(context.path("name").asText()).isEqualTo("박보호");
        assertThat(context.path("phone").asText()).isEqualTo("010-2222-3333");
        verify(connectionService, never()).isActiveConnection(any(), any());
    }

    @Test
    @DisplayName("보호자 본인 상담(wardId 없음): 클라이언트 name·phone 위조는 보호자 DB 값으로 바뀐다")
    void 보호자_본인_상담은_보호자_DB_값() throws Exception {
        when(userRepository.findById(GUARDIAN)).thenReturn(Optional.of(guardian()));
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));
        Map<String, Object> context = new HashMap<>();
        context.put("name", "위조이름");
        context.put("phone", "010-9999-9999");

        service.sendAsGuardian(GUARDIAN, withContext(context));

        JsonNode sent = sentBody().path("context");
        assertThat(sent.path("name").asText()).isEqualTo("박보호");
        assertThat(sent.path("phone").asText()).isEqualTo("010-2222-3333");
        assertThat(sent.toString()).doesNotContain("위조이름").doesNotContain("9999");
        verify(connectionService, never()).isActiveConnection(any(), any());
    }

    @Test
    @DisplayName("피보호자(본인) 경로: 대리 키는 지우고 name·phone 은 로그인한 본인의 DB 값이다(클라이언트 위조 무시)")
    void 피보호자_경로는_대리_키를_지운다() throws Exception {
        when(userRepository.findById(WARD)).thenReturn(Optional.of(ward()));
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));
        Map<String, Object> forged = forged("VICTIM");
        forged.put("phone", "010-7777-7777");

        service.send(WARD, withContext(forged));

        JsonNode context = sentBody().path("context");
        assertThat(context.has("wardId")).isFalse();
        assertThat(context.has("wardName")).isFalse();
        assertThat(context.has("wardPhone")).isFalse();
        assertThat(context.path("name").asText()).isEqualTo("김영희");
        assertThat(context.path("phone").asText()).isEqualTo("010-1234-5678");
        assertThat(context.toString()).doesNotContain("보호자").doesNotContain("7777").doesNotContain("위조");
        verify(userRepository).findById(WARD);
        verifyNoInteractions(connectionService);
    }

    @Test
    @DisplayName("피보호자 본인 상담: DB 에 전화가 없으면 phone 키를 보내지 않는다(클라이언트 phone 도 제거)")
    void 피보호자_본인_전화_없음() throws Exception {
        when(userRepository.findById(WARD)).thenReturn(Optional.of(User.builder().id(WARD).name("김영희").build()));
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));
        Map<String, Object> context = new HashMap<>();
        context.put("phone", "010-7777-7777");

        service.send(WARD, withContext(context));

        JsonNode sent = sentBody().path("context");
        assertThat(sent.path("name").asText()).isEqualTo("김영희");
        assertThat(sent.has("phone")).isFalse();
    }

    @Test
    @DisplayName("피보호자 본인 상담: 본인 행이 없으면 name·phone 없이 진행하고 클라이언트 값은 남지 않는다")
    void 본인_행이_없으면_이름_전화_없이() throws Exception {
        when(userRepository.findById(WARD)).thenReturn(Optional.empty());
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));
        Map<String, Object> context = new HashMap<>();
        context.put("name", "위조이름");
        context.put("phone", "010-7777-7777");

        service.send(WARD, withContext(context));

        JsonNode sent = sentBody().path("context");
        assertThat(sent.has("name")).isFalse();
        assertThat(sent.has("phone")).isFalse();
        assertThat(sent.path("role").asText()).isEqualTo("WARD");
    }

    @Test
    @DisplayName("형식이 틀린 wardId(줄바꿈·7자 이상·8KB)는 DB 조회·AI 호출 없이 403이고 로그에 원문이 남지 않는다")
    void 형식이_틀린_wardId는_원문_없이_거절() {
        Logger logger = (Logger) LoggerFactory.getLogger(ChatRelayService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (String bad : new String[]{"ab\ncd", "abcdefg", "a".repeat(5000), "../x", "a b"}) {
                assertThatThrownBy(() -> service.sendAsGuardian(GUARDIAN, withContext(forged(bad))))
                        .isInstanceOfSatisfying(CustomException.class,
                                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_WARD_NOT_CONNECTED));
            }
        } finally {
            logger.detachAppender(appender);
        }

        verifyNoInteractions(aiChatClient, connectionService, userRepository);
        assertThat(appender.list).hasSize(5);
        for (ILoggingEvent event : appender.list) {
            String line = event.getFormattedMessage();
            assertThat(line).contains("[IDOR-ATTEMPT]").contains("guardianId=" + GUARDIAN);
            assertThat(line).doesNotContain("\n").doesNotContain("aaaaa").doesNotContain("../");
            assertThat(line.length()).isLessThan(200);
        }
    }

    @Test
    @DisplayName("속도 제한은 wardId 확인보다 먼저 - 한도 초과면 연결 조회·DB 조회를 하지 않는다")
    void 속도_제한이_wardId_확인보다_먼저() {
        doThrow(new CustomException(ErrorCode.CHAT_LIMIT_EXCEEDED))
                .when(rateLimitService).check(anyString(), eq(GUARDIAN), anyInt(), anyInt());

        assertThatThrownBy(() -> service.sendAsGuardian(GUARDIAN, withContext(forged("VICTIM"))))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_LIMIT_EXCEEDED));

        verifyNoInteractions(connectionService, userRepository, aiChatClient);
    }

    @Test
    @DisplayName("AI 응답 뒤 연결이 끊겨 있으면 응답을 폐기하고 403")
    void 응답_뒤_연결_해제면_응답_폐기() throws Exception {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true, false);
        when(userRepository.findById(WARD)).thenReturn(Optional.of(ward()));
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{\"reply\":\"피보호자 예약 내용\"}"));

        assertThatThrownBy(() -> service.sendAsGuardian(GUARDIAN, withContext(forged(WARD))))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_WARD_NOT_CONNECTED));

        verify(aiChatClient).send(any());
    }

    @Test
    @DisplayName("대리 상담의 name·phone 도 클라이언트 값 대신 DB 값이다")
    void name_phone도_DB_값() throws Exception {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        when(userRepository.findById(WARD)).thenReturn(Optional.of(ward()));
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));
        Map<String, Object> withPhone = forged(WARD);
        withPhone.put("phone", "010-7777-7777");

        service.sendAsGuardian(GUARDIAN, withContext(withPhone));

        JsonNode context = sentBody().path("context");
        assertThat(context.path("name").asText()).isEqualTo("김영희");
        assertThat(context.path("phone").asText()).isEqualTo("010-1234-5678");
        assertThat(context.toString()).doesNotContain("7777").doesNotContain("보호자");
    }

    @Test
    @DisplayName("DB 에 전화가 없는 피보호자: 클라이언트가 보낸 phone 은 AI 에 가지 않는다")
    void DB_전화가_없으면_클라이언트_phone_제거() throws Exception {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        when(userRepository.findById(WARD)).thenReturn(Optional.of(
                User.builder().id(WARD).name("김영희").build()));
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));
        Map<String, Object> context = forged(WARD);
        context.put("phone", "010-7777-7777");

        service.sendAsGuardian(GUARDIAN, withContext(context));

        JsonNode sent = sentBody().path("context");
        assertThat(sent.has("phone")).isFalse();
        assertThat(sent.has("wardPhone")).isFalse();
        assertThat(sent.path("name").asText()).isEqualTo("김영희");
    }

    @Test
    @DisplayName("context.role 은 경로의 역할로 덮어쓴다 - 피보호자 경로는 WARD (클라이언트 값·누락 무시, AI 챗 집계 근거)")
    void 피보호자_경로는_role_WARD() throws Exception {
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));
        Map<String, Object> context = new HashMap<>();
        context.put("role", "GUARDIAN");

        service.send(WARD, withContext(context));
        assertThat(sentBody().path("context").path("role").asText()).isEqualTo("WARD");
    }

    @Test
    @DisplayName("context 가 없어도 피보호자 경로는 role=WARD 를 싣는다")
    void context_없어도_role_WARD() throws Exception {
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));

        service.send(WARD, withContext(null));
        assertThat(sentBody().path("context").path("role").asText()).isEqualTo("WARD");
    }

    @Test
    @DisplayName("보호자 경로는 본인·대리 상담 모두 role=GUARDIAN (클라이언트가 WARD 를 보내도)")
    void 보호자_경로는_role_GUARDIAN() throws Exception {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        when(userRepository.findById(WARD)).thenReturn(Optional.of(ward()));
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));
        Map<String, Object> proxy = forged(WARD);
        proxy.put("role", "WARD");

        service.sendAsGuardian(GUARDIAN, withContext(proxy));
        service.sendAsGuardian(GUARDIAN, withContext(Map.of("role", "ward")));

        ArgumentCaptor<byte[]> bodies = ArgumentCaptor.forClass(byte[].class);
        verify(aiChatClient, times(2)).send(bodies.capture());
        for (byte[] b : bodies.getAllValues()) {
            assertThat(objectMapper.readTree(b).path("context").path("role").asText()).isEqualTo("GUARDIAN");
        }
    }

    // ---- 환자 프로필 키 (2026-11-04) ----

    private static Map<String, Object> forgedProfile() {
        Map<String, Object> context = new HashMap<>();
        context.put("age", 99);
        context.put("birthDate", "1900-01-01");
        context.put("gender", "MALE");
        context.put("email", "forged@example.com");
        context.put("address", "위조시 위조구");
        context.put("addressDetail", "101동");
        context.put("postcode", "99999");
        context.put("relation", "SON");
        context.put("patientId", "VICTIM");
        context.put("patientName", "위조환자");
        context.put("patientBirthDate", "1900-01-01");
        context.put("patientPhone", "010-9999-9999");
        context.put("patientGender", "MALE");
        context.put("location", "서울 강남구");
        return context;
    }

    private User fullWard() {
        return User.builder().id(WARD).name("김영희").phone("010-1234-5678").gender(Gender.FEMALE)
                .birthDate(LocalDate.of(1950, 3, 1)).address("서울 서초구 반포대로 1").addressDetail("101호")
                .postcode("06500").email("ward@example.com").build();
    }

    private static final java.util.List<String> NEVER_SENT = java.util.List.of(
            "email", "addressDetail", "postcode", "relation",
            "patientId", "patientName", "patientBirthDate", "patientPhone", "patientGender");

    @Test
    @DisplayName("피보호자 본인 상담: 위조 프로필 키는 버리고 생년월일·나이·성별·주소를 본인 DB 값으로 채운다(location 은 그대로)")
    void 본인_상담_프로필은_DB_값() throws Exception {
        when(userRepository.findById(WARD)).thenReturn(Optional.of(fullWard()));
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));

        service.send(WARD, withContext(forgedProfile()));

        JsonNode sent = sentBody().path("context");
        assertThat(sent.path("birthDate").asText()).isEqualTo("1950-03-01");
        assertThat(sent.path("age").asInt()).isEqualTo(java.time.Period.between(LocalDate.of(1950, 3, 1),
                LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))).getYears());
        assertThat(sent.path("gender").asText()).isEqualTo("FEMALE");
        assertThat(sent.path("address").asText()).isEqualTo("서울 서초구 반포대로 1");
        assertThat(sent.path("location").asText()).isEqualTo("서울 강남구");
        for (String key : NEVER_SENT) {
            assertThat(sent.has(key)).as(key).isFalse();
        }
        assertThat(sent.toString()).doesNotContain("위조").doesNotContain("1900").doesNotContain("VICTIM")
                .doesNotContain("101호").doesNotContain("06500").doesNotContain("ward@example.com");
    }

    @Test
    @DisplayName("대리 상담: 프로필 키는 피보호자 DB 값이고 클라이언트 patient*·relation 은 AI 에 가지 않는다")
    void 대리_상담_프로필은_피보호자_DB_값() throws Exception {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        when(userRepository.findById(WARD)).thenReturn(Optional.of(fullWard()));
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));
        Map<String, Object> context = forgedProfile();
        context.put("wardId", WARD);

        service.sendAsGuardian(GUARDIAN, withContext(context));

        JsonNode sent = sentBody().path("context");
        assertThat(sent.path("wardId").asText()).isEqualTo(WARD);
        assertThat(sent.path("gender").asText()).isEqualTo("FEMALE");
        assertThat(sent.path("birthDate").asText()).isEqualTo("1950-03-01");
        assertThat(sent.path("address").asText()).isEqualTo("서울 서초구 반포대로 1");
        for (String key : NEVER_SENT) {
            assertThat(sent.has(key)).as(key).isFalse();
        }
        assertThat(sent.toString()).doesNotContain("위조").doesNotContain("1900");
    }

    @Test
    @DisplayName("DB 에 생년월일·성별·주소가 없으면 그 키를 보내지 않는다(클라이언트 값도 남지 않는다)")
    void 빈_프로필은_키_없음() throws Exception {
        when(userRepository.findById(WARD)).thenReturn(Optional.of(User.builder().id(WARD).name("김영희").build()));
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));

        service.send(WARD, withContext(forgedProfile()));

        JsonNode sent = sentBody().path("context");
        for (String key : java.util.List.of("age", "birthDate", "gender", "address", "phone")) {
            assertThat(sent.has(key)).as(key).isFalse();
        }
        assertThat(sent.path("name").asText()).isEqualTo("김영희");
    }

    @Test
    @DisplayName("만 나이는 생일 당일에 한 살 오르고, 미래 생년월일은 보내지 않는다")
    void 만_나이_경계() {
        User user = User.builder().id(WARD).name("김영희").birthDate(LocalDate.of(1950, 3, 1)).build();
        Map<String, Object> dayBefore = new HashMap<>();
        Map<String, Object> birthday = new HashMap<>();
        ChatRelayService.putProfile(dayBefore, user, LocalDate.of(2026, 2, 28));
        ChatRelayService.putProfile(birthday, user, LocalDate.of(2026, 3, 1));
        assertThat(dayBefore.get("age")).isEqualTo(75);
        assertThat(birthday.get("age")).isEqualTo(76);

        Map<String, Object> future = new HashMap<>();
        ChatRelayService.putProfile(future,
                User.builder().id(WARD).name("김영희").birthDate(LocalDate.of(2030, 1, 1)).build(), LocalDate.of(2026, 3, 1));
        assertThat(future).doesNotContainKeys("age", "birthDate");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "서울 강남구 테헤란로 1", "서울특별시 서초구 반포대로 1", "경기 성남시 분당구 판교로 1", "경기도 수원시 팔달구",
            "부산광역시 해운대구 우동", "제주특별자치도 제주시 연동", "강원특별자치도 춘천시", "충청북도 청주시 상당구",
            "세종특별자치시 한누리대로 2130", "세종 다솜로 1", "  서울 강남구  "})
    @DisplayName("시·도 + 시·군·구(세종은 시·도만)로 시작하는 주소는 AI 병원 검색에 보낸다")
    void 검색할_수_있는_주소(String address) {
        assertThat(ChatRelayService.isSearchableAddress(address)).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "<주소>", "주소", "서울", "서울특별시", "강남구 테헤란로", "Seoul Gangnam-gu", "123 Main St",
            "테스트 주소", "서울 테헤란로 1", "서울시청", " "})
    @DisplayName("자리표시·외국·시·도만 있는 주소는 보내지 않는다 - AI 가 원문으로 검색해 0건이 되기 때문")
    void 검색할_수_없는_주소(String address) {
        assertThat(ChatRelayService.isSearchableAddress(address)).isFalse();
    }

    @Test
    @DisplayName("자리표시 주소(<주소>)인 피보호자: address 키 없이 보내 AI 가 지역을 묻게 한다(클라이언트 address 도 버림)")
    void 자리표시_주소는_보내지_않는다() throws Exception {
        when(userRepository.findById(WARD)).thenReturn(Optional.of(
                User.builder().id(WARD).name("김영희").address("<주소>").build()));
        when(aiChatClient.send(any())).thenReturn(objectMapper.readTree("{}"));
        Map<String, Object> context = new HashMap<>();
        context.put("address", "서울 강남구 위조로 1");

        service.send(WARD, withContext(context));

        JsonNode sent = sentBody().path("context");
        assertThat(sent.has("address")).isFalse();
        assertThat(sent.path("name").asText()).isEqualTo("김영희");
    }
}
