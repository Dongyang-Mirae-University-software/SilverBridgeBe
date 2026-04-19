package kr.silverbridge.main.domain.user.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import lombok.*;
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

    @Column(nullable = false, length = 50)
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

    // 계정 비활성화
    public void deactivate() {
        this.status = Status.INACTIVE;
    }

    // 프로필 정보 수정 (이름, 전화번호, 주소)
    public void updateProfile(String name, String phone, String address, String addressDetail) {
        this.name = name;
        this.phone = phone;
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
        return provider != Provider.LOCAL;
    }

}
