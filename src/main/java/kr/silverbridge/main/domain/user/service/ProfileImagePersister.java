package kr.silverbridge.main.domain.user.service;

import kr.silverbridge.main.domain.user.dto.UserProfileResponse;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로필 이미지 URL의 DB 영속화만 담당하는 트랜잭션 협력자 (D-USER-1).
 * <p>
 * 파일 서버 업로드(외부 HTTP)는 {@link UserService#updateProfileImage}가 트랜잭션 밖에서 수행하고,
 * 그 결과 URL의 영속화만 이 빈의 {@code @Transactional} 메서드(프록시 경유)에서 처리한다.
 * 트랜잭션 경계를 구조적으로 분리해 업로드 동안 DB 커넥션을 점유하지 않게 한다.
 * 엔티티 dirty checking을 사용하므로 {@code @LastModifiedDate}(updated_at) 갱신은 그대로 유지된다.
 */
@Component
@RequiredArgsConstructor
public class ProfileImagePersister {

    private final UserRepository userRepository;

    // 새 이미지 URL을 영속화하고, 교체 전 URL(삭제 대상)과 갱신된 프로필을 반환한다.
    @Transactional
    public Result replace(String userId, String newImageUrl) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        String oldImageUrl = user.getProfileImage();
        user.updateProfileImage(newImageUrl);
        return new Result(oldImageUrl, UserProfileResponse.from(user));
    }

    public record Result(String oldImageUrl, UserProfileResponse response) {}
}
