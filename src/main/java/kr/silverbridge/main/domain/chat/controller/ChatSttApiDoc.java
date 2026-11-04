package kr.silverbridge.main.domain.chat.controller;

/** 챗봇 마이크 음성 인식 API 설명(피보호자·보호자 경로 공용). */
final class ChatSttApiDoc {

    static final String DESCRIPTION = """
            [요청 헤더]
            Authorization: Bearer {accessToken}

            챗봇 화면에서 마이크로 녹음한 음성 파일 1개를 multipart 필드 audio로 보내면 받아 적은 글자를 돌려줍니다(data.text).
            글자는 입력창에 채우기만 하고, 사용자가 보내기를 누르면 기존 챗 전송 경로로 보냅니다. 음성과 글자는 서버에 저장하지 않습니다.
            못 알아들으면 text는 빈 글자("")입니다 - "다시 말씀해 주세요"로 안내하세요.

            형식 WAV·WebM·MP3(파일 앞부분으로 판별), 10MB·30초 이하. 1인 분당 10회·시간당 100회, 한 번에 1건.
            서버는 최대 30초 기다리므로 FE 타임아웃은 그보다 길게(권장 35초) 잡으세요.

            [오류 code]
            CHAT_STT_AUDIO_INVALID(400, 파일 없음·형식 오류·읽을 수 없음) / CHAT_STT_AUDIO_TOO_LARGE(413, 10MB·30초 초과) /
            CHAT_STT_BUSY(503, "잠시 후 다시 눌러 주세요") / CHAT_STT_UNAVAILABLE(503, 음성 인식 서버 불가·시간 초과) /
            CHAT_STT_DISABLED(503, 기능 꺼짐) / TOO_MANY_REQUESTS(429, Retry-After 헤더)
            multipart가 아닌 요청은 UNSUPPORTED_MEDIA_TYPE(415), 깨진 multipart 본문은 FILE_REQUIRED(400)입니다 -
            둘 다 녹음 실패로 보고 "다시 말씀해 주세요"로 안내하세요.
            """;

    private ChatSttApiDoc() {
    }
}
