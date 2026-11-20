package kr.silverbridge.main.domain.medication.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 약 목록 기준 표시 이름(2026-11-20 사용자 결정): 같은 피보호자의 약 중 짧은 이름이 겹치는 약만
 * "짧은 이름(용량)"이고, 용량 단위는 약자로 통일한다. 겹치지 않는 약은 {@link MedicationDisplayName#of} 그대로다.
 */
class MedicationDisplayNameListTest {

    private static Map<String, String> ofAll(String... names) {
        return MedicationDisplayName.ofAll(Arrays.asList(names));
    }

    @Test
    @DisplayName("짧은 이름이 겹치는 약만 '이름(용량)' - 한글 단위는 약자로, 겹치지 않는 약은 짧은 이름 그대로")
    void collidingNamesGetDose() {
        Map<String, String> names = ofAll(
                "다이아벡스정500밀리그램(메트포르민염산염)",
                "다이아벡스정850밀리그램(메트포르민염산염)",
                "아스피린장용정100mg");

        assertThat(names).containsEntry("다이아벡스정500밀리그램(메트포르민염산염)", "다이아벡스정(500mg)")
                .containsEntry("다이아벡스정850밀리그램(메트포르민염산염)", "다이아벡스정(850mg)")
                .containsEntry("아스피린장용정100mg", "아스피린장용정");
    }

    @Test
    @DisplayName("셋 이상 겹쳐도 각자 용량을 붙인다")
    void threeWayCollision() {
        Map<String, String> names = ofAll("노바스크정2.5밀리그람(암로디핀베실산염)", "노바스크정5밀리그람(암로디핀베실산염)",
                "노바스크정10밀리그람(암로디핀베실산염)");

        assertThat(names.values()).containsExactlyInAnyOrder("노바스크정(2.5mg)", "노바스크정(5mg)", "노바스크정(10mg)");
    }

    @Test
    @DisplayName("복합 용량·농도·마이크로그램·단위(U)·대문자·띄어쓴 단위도 약자로 통일하고 공백을 뺀다")
    void unitAbbreviation() {
        assertThat(ofAll("로수탄메트정500/5밀리그램", "로수탄메트정1000/5밀리그램"))
                .containsEntry("로수탄메트정500/5밀리그램", "로수탄메트정(500/5mg)")
                .containsEntry("로수탄메트정1000/5밀리그램", "로수탄메트정(1000/5mg)");
        assertThat(ofAll("코푸시럽500mg/5mL", "코푸시럽250mg/5ml"))
                .containsEntry("코푸시럽500mg/5mL", "코푸시럽(500mg/5mL)")
                .containsEntry("코푸시럽250mg/5ml", "코푸시럽(250mg/5mL)");
        assertThat(ofAll("뉴로메틴캅셀500마이크로그람(메코발라민)", "뉴로메틴캅셀1500마이크로그람(메코발라민)"))
                .containsEntry("뉴로메틴캅셀500마이크로그람(메코발라민)", "뉴로메틴캅셀(500mcg)")
                .containsEntry("뉴로메틴캅셀1500마이크로그람(메코발라민)", "뉴로메틴캅셀(1500mcg)");
        assertThat(ofAll("휴물린주200단위", "휴물린주100단위/밀리리터", "헤파린주25000국제단위"))
                .containsEntry("휴물린주200단위", "휴물린주(200U)")
                .containsEntry("휴물린주100단위/밀리리터", "휴물린주(100U/mL)")
                .containsEntry("헤파린주25000국제단위", "헤파린주");
        assertThat(ofAll("가나정 500 MG", "가나정 1000 MG"))
                .containsEntry("가나정 500 MG", "가나정(500mg)")
                .containsEntry("가나정 1000 MG", "가나정(1000mg)");
    }

    @Test
    @DisplayName("용량이 괄호 안에 있어도 그 용량을 쓴다 - '아모디핀정(5mg)'")
    void doseInsideBrackets() {
        assertThat(ofAll("아모디핀정(5mg)", "아모디핀정(10밀리그램)"))
                .containsEntry("아모디핀정(5mg)", "아모디핀정(5mg)")
                .containsEntry("아모디핀정(10밀리그램)", "아모디핀정(10mg)");
    }

    @Test
    @DisplayName("용량까지 같으면(표기만 다름) 끝쪽 괄호만 뗀 이름으로 구분한다")
    void sameDoseFallsBackToBracketless() {
        assertThat(ofAll("다이아벡스정500mg(메트포르민염산염)", "다이아벡스정 500밀리그램(메트포르민염산염)"))
                .containsEntry("다이아벡스정500mg(메트포르민염산염)", "다이아벡스정500mg")
                .containsEntry("다이아벡스정 500밀리그램(메트포르민염산염)", "다이아벡스정 500밀리그램");
    }

    @Test
    @DisplayName("붙일 용량이 없고 괄호를 떼도 같으면 짧은 이름 그대로")
    void noDoseKeepsShortName() {
        assertThat(ofAll("비타민정", "비타민정(종합)"))
                .containsEntry("비타민정", "비타민정")
                .containsEntry("비타민정(종합)", "비타민정");
    }

    @Test
    @DisplayName("같은 원래 이름(같은 약 두 번 등록·앞뒤 공백)은 겹침으로 세지 않는다, null은 건너뛴다")
    void sameOriginalIsNotCollision() {
        Map<String, String> names = MedicationDisplayName.ofAll(Arrays.asList("아모디핀정5mg", " 아모디핀정5mg ", null));

        assertThat(names).containsOnly(Map.entry("아모디핀정5mg", "아모디핀정"));
    }

    @Test
    @DisplayName("직접 입력한 설명 괄호가 있는 이름은 짧은 이름부터 달라 겹치지 않는다")
    void userWrittenNamesUnchanged() {
        assertThat(ofAll("혈압약 (암로디핀 5mg)", "혈압약 (암로디핀 10mg)"))
                .containsEntry("혈압약 (암로디핀 5mg)", "혈압약 (암로디핀 5mg)")
                .containsEntry("혈압약 (암로디핀 10mg)", "혈압약 (암로디핀 10mg)");
        assertThat(ofAll(List.of()).isEmpty()).isTrue();
    }

    private static Map<String, String> ofAll(List<String> names) {
        return MedicationDisplayName.ofAll(names);
    }
}
