package kr.silverbridge.main.domain.camera.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 카메라 등록·방 이름 변경 화면의 방 선택지 1건. {@code registered=true}면 이 피보호자가 이미 그 방에 카메라를 두었다
 * ("· 등록됨", 선택 불가 - 한 방에 카메라 1대).
 */
@Schema(description = "카메라 방 선택지")
public record CameraRoomOption(
        @Schema(description = "방 이름(등록·수정 요청의 label로 그대로 보낸다)", example = "거실")
        String label,
        @Schema(description = "이미 카메라가 등록된 방인지", example = "false")
        boolean registered
) {}
