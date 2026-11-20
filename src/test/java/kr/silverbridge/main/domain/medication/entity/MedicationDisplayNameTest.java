package kr.silverbridge.main.domain.medication.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 약 표시 이름 규칙(2026-11-19). 표본은 AI 약 대조 목록(drug_names.txt, 42,744건)의 패턴별 실제 이름과
 * 직접 입력 예시다 - PHASE 0 표.
 */
class MedicationDisplayNameTest {

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("허가 품목명은 끝쪽 용량과 성분 괄호를 뗀다")
    @CsvSource(delimiter = '|', textBlock = """
            다이아벡스정500밀리그램(메트포르민염산염)                         | 다이아벡스정
            쎄레브렉스캡슐200밀리그램                                          | 쎄레브렉스캡슐
            아스피린프로텍트정100밀리그램                                      | 아스피린프로텍트정
            로수탄메트정500/5밀리그램                                          | 로수탄메트정
            시타민정50/1000밀리그램(시타글립틴인산염, 메트포르민염산염)        | 시타민정
            노바스크정5밀리그람(암로디핀베실산염)                              | 노바스크정
            뉴로메틴캅셀500마이크로그람(메코발라민)(수출명:필발라민캡슐)(수출용) | 뉴로메틴캅셀
            보령토르세미드정10밀리그람(수출명:DUREMID 10mg Tablet)             | 보령토르세미드정
            실데나킴정100밀리그램 (실데나필시트르산염)                         | 실데나킴정
            베로핀정5mg                                                        | 베로핀정
            타이레놀정500mg                                                    | 타이레놀정
            휴온스아미노주3%                                                   | 휴온스아미노주
            씨디에스에이산0.1%                                                 | 씨디에스에이산
            어택트라흡입용캡슐150/80마이크로그램                               | 어택트라흡입용캡슐
            비엠헤파린나트륨주사20000단위                                      | 비엠헤파린나트륨주사
            동구콜레칼시페롤주300,000IU                                        | 동구콜레칼시페롤주
            일성클래리트로마이신건조시럽125mg/5mL                              | 일성클래리트로마이신건조시럽
            로카핀주7.5밀리그램/밀리리터(로피바카인염산염수화물)               | 로카핀주
            레버미어플렉스펜주100단위/밀리리터                                 | 레버미어플렉스펜주
            이트라졸캡슐(이트라코나졸)(수출용)                                 | 이트라졸캡슐
            나소프라정500/20                                                   | 나소프라정
            타이레놀 500mg                                                     | 타이레놀
            """)
    void stripsDoseAndIngredient(String name, String expected) {
        assertThat(MedicationDisplayName.of(name)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("이름 가운데 숫자는 떼지 않고 끝쪽 용량만 뗀다")
    @CsvSource(delimiter = '|', textBlock = """
            타세놀8시간이알서방정325밀리그램(아세트아미노펜) | 타세놀8시간이알서방정
            타이레놀8시간이알서방정                          | 타이레놀8시간이알서방정
            인스콘600에프정                                  | 인스콘600에프정
            대우시메티딘300밀리그람정                        | 대우시메티딘300밀리그람정
            이노엔염산도파민프리믹스80mg주                   | 이노엔염산도파민프리믹스80mg주
            """)
    void keepsMiddleNumbers(String name, String expected) {
        assertThat(MedicationDisplayName.of(name)).isEqualTo(expected);
    }

    @ParameterizedTest
    @DisplayName("직접 입력한 설명 괄호·단위 없는 끝 숫자·이미 짧은 이름은 그대로 둔다")
    @ValueSource(strings = {
            "혈압약 (암로디핀 5mg)",
            "아침약(혈압)",
            "비타민D3",
            "가다실9프리필드시린지[인유두종바이러스9가(6,11,16,18,31,33,45,52,58형)백신(유전자재조합)]",
            "다이아벡스정",
            "혈압약",
            "(주)창신의료용산소",
            "나소프라정500/20m"
    })
    void keepsNamesThatAreNotLicensedForm(String name) {
        assertThat(MedicationDisplayName.of(name)).isEqualTo(name);
    }

    @ParameterizedTest
    @DisplayName("떼고 나면 빈 문자열이 되는 이름은 원본을 돌려준다")
    @ValueSource(strings = {"500mg", "0.9%", "(메트포르민염산염)", "500/5밀리그램"})
    void returnsOriginalWhenNothingLeft(String name) {
        assertThat(MedicationDisplayName.of(name)).isEqualTo(name);
    }

    @Test
    @DisplayName("null은 null, 앞뒤 공백은 정리한다")
    void nullAndWhitespace() {
        assertThat(MedicationDisplayName.of(null)).isNull();
        assertThat(MedicationDisplayName.of("  쎄레브렉스캡슐200밀리그램  ")).isEqualTo("쎄레브렉스캡슐");
    }
}
