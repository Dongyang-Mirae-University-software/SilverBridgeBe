package kr.silverbridge.main.domain.user.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import kr.silverbridge.main.global.enums.Gender;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import lombok.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class User extends BaseTimeEntity {

    @Id
    @Column(length = 6)
    private String id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(length = 255)
    private String password;

    @Column(nullable = false, length = 20)
    private String name;

    @Column(length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Provider provider;

    @Column(name = "provider_id", length = 100)
    private String providerId;

    @Column(name = "profile_image", length = 500)
    private String profileImage;

    // 성별 (여성/남성). 기존 사용자는 NULL(미입력) — V18에서 NULL 허용으로 추가.
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Gender gender;

    // 생년월일. 기존 사용자는 NULL(미입력).
    @Column(name = "birth_date")
    private LocalDate birthDate;

    // 우편번호 (카카오 주소 API zonecode). 기존 사용자는 NULL(미입력).
    @Column(length = 10)
    private String postcode;

    @Column(nullable = false, length = 200)
    private String address;

    @Column(name = "address_detail", nullable = false, length = 100)
    private String addressDetail;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    // 마지막 로그인 시간 갱신
    public void updateLastLoginAt() {
        this.lastLoginAt = OffsetDateTime.now();
    }

    // 비밀번호 변경
    public void updatePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    // 카카오 신규 가입 시 역할 확정 및 ACTIVE 전환
    public void completeRole(Role role) {
        this.role = role;
        this.status = Status.ACTIVE;
    }

    // 계정 활성화
    public void activate() {
        this.status = Status.ACTIVE;
    }

    // 계정 비활성화 (탈퇴 전용)
    // 스윕 스케줄러가 오래된 INACTIVE 행을 좀비로 보고 영구 삭제하므로, 관리자 정지 등
    // 다른 용도로 이 메서드를 재사용하지 말 것 (2026-06-11 INACTIVE 불변식). 정지는 restrict().
    public void deactivate() {
        this.status = Status.INACTIVE;
    }

    // 계정 이용 제한 (관리자 정지) - 로그인·토큰 재발급이 막히지만 데이터는 그대로 남는다.
    public void restrict() {
        this.status = Status.RESTRICTED;
    }

    // 이름만 수정 (관리자 회원관리) - 이메일·전화번호는 관리자가 바꿀 수 없다.
    // 전화번호는 본인 경로에서 SMS 인증 nonce 소비가 필수라(H-5) 관리자 경로를 열면 그 인증을 우회하고,
    // 이메일은 본인조차 바꿀 수 없는 값이다.
    public void changeName(String name) {
        this.name = name;
    }

    // 프로필 정보 수정 (이름, 전화번호, 성별, 생년월일, 우편번호, 주소)
    // 기존 사용자도 프로필 수정 시 성별·생년월일·우편번호를 함께 보완 입력한다.
    public void updateProfile(String name, String phone, Gender gender, LocalDate birthDate,
                              String postcode, String address, String addressDetail) {
        this.name = name;
        this.phone = phone;
        this.gender = gender;
        this.birthDate = birthDate;
        this.postcode = postcode;
        this.address = address;
        this.addressDetail = addressDetail;
    }

    // 프로필 이미지 변경
    public void updateProfileImage(String profileImageUrl) {
        this.profileImage = profileImageUrl;
    }

    // 역할 변경 (WARD ↔ GUARDIAN, ADMIN 전환 불가)
    public void updateRole(Role role) {
        this.role = role;
    }

    // 로컬(일반) 회원 여부 확인
    public boolean isLocalProvider() {
        return provider == Provider.LOCAL;
    }

    // 소셜(카카오) 회원 여부 확인
    public boolean isSocialProvider() {
        return provider == Provider.KAKAO;
    }

}
