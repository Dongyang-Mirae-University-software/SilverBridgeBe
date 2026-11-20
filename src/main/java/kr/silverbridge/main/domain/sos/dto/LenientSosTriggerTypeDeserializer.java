package kr.silverbridge.main.domain.sos.dto;

import kr.silverbridge.main.domain.sos.entity.SosTriggerType;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * SOS 발생 요청의 발생 경로({@code triggerType})를 너그럽게 읽는다(2026-11-20 영향 범위 점검 M-1, 사용자 결정).
 *
 * <p>서버가 모르는 값(FE가 BE보다 먼저 새 경로를 보낸 경우·오타)이나 글자가 아닌 값(숫자·객체·배열)이 오면
 * 400으로 SOS 전체를 버리지 않고 {@link SosTriggerType#SOS_BUTTON}으로 접수한다 - SOS는 어떤 경우에도 잃지 않는다.
 * 대신 이력에서 그 건은 SOS 버튼으로 보이는 손실을 받아들인다.</p>
 *
 * <ul>
 *   <li>이름이 <b>정확히</b> 일치할 때만 그 경로로 받는다 - 숫자·숫자 글자를 순번으로 받지 않는다
 *       ({@code WebJsonEnumNumberRejectTest}와 같은 이유: {@code "1"}이 GUARDIAN_CALL이 되면 안 된다).</li>
 *   <li>로그에는 값 원문을 남기지 않고 길이·토큰 종류만 남긴다(줄바꿈 삽입·긴 값으로 로그 위조 방지).</li>
 *   <li>JSON {@code null}은 여기를 거치지 않고 null이 되어 엔티티가 SOS_BUTTON으로 기록한다(기존 동작).</li>
 * </ul>
 *
 * <p><b>이 역직렬화기는 {@code POST /api/ward/sos} 요청 바디({@link SosTriggerRequest})에만 붙인다.</b>
 * 보호자 이력 필터({@code GET /api/guardian/sos/history?triggerType=})는 조회라 잃을 것이 없어 400을 유지한다.</p>
 */
@Slf4j
public class LenientSosTriggerTypeDeserializer extends ValueDeserializer<SosTriggerType> {

    @Override
    public SosTriggerType deserialize(JsonParser p, DeserializationContext ctxt) {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_STRING) {
            String raw = p.getString();
            for (SosTriggerType type : SosTriggerType.values()) {
                if (type.name().equals(raw)) {
                    return type;
                }
            }
            log.warn("[SOS-TRIGGER-UNKNOWN] 모르는 발생 경로라 SOS_BUTTON으로 접수: reason=UNKNOWN_VALUE, length={}",
                    raw == null ? 0 : raw.length());
            return SosTriggerType.SOS_BUTTON;
        }
        // 객체·배열이면 끝까지 건너뛰어야 나머지 바디(location 등)를 계속 읽는다. 스칼라는 아무 일도 하지 않는다.
        p.skipChildren();
        log.warn("[SOS-TRIGGER-UNKNOWN] 글자가 아닌 발생 경로라 SOS_BUTTON으로 접수: reason=NOT_STRING, token={}", token);
        return SosTriggerType.SOS_BUTTON;
    }
}
