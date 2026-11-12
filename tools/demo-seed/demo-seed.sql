-- 관리자 대시보드 데모 데이터 (gosky dev 전용). 시각은 실행 시점(KST 오늘) 기준이라 시연 당일 다시 돌리면 "오늘" 수치가 다시 찬다.
-- 안전장치: 가짜 회원은 비밀번호·전화번호 없음(로그인·문자 불가), FCM 토큰 없음, 재촉 끔(guardian_anomaly_setting),
--           카메라는 만들지 않음(서비스 상한 3대·AI 구독 대상이라 실사용자 등록을 막는다).
-- 실행: tools/demo-seed/run.sh (지운 뒤 다시 넣음) / tools/demo-seed/run.sh clean (지우기만)
-- 단독 실행 금지: demo-cleanup.sql을 먼저 돌리지 않으면 같은 id로 INSERT가 실패한다.
BEGIN;

CREATE TEMP TABLE t AS
SELECT date_trunc('day', now() AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul' AS d0, now() AS n;

-- 1) 회원 24명 (최근 7일 가입 18명 + 그 이전 6명)
CREATE TEMP TABLE du(id text, name text, role text, gender text, birth date, addr text, days_ago int, frac numeric);
INSERT INTO du VALUES
 ('dmoW01','김순자','WARD','FEMALE','1944-03-12','서울 노원구 상계로 45',0,0.25),
 ('dmoG01','김민준','GUARDIAN','MALE','1976-07-02','서울 노원구 동일로 1400',0,0.45),
 ('dmoW02','이영식','WARD','MALE','1941-11-30','경기 고양시 일산동구 중앙로 1200',0,0.70),
 ('dmoW03','박말순','WARD','FEMALE','1946-05-21','인천 남동구 예술로 150',1,0.40),
 ('dmoG02','박지현','GUARDIAN','FEMALE','1979-01-15','인천 연수구 송도과학로 32',1,0.60),
 ('dmoW04','최정호','WARD','MALE','1943-09-08','서울 강서구 화곡로 302',2,0.30),
 ('dmoG03','최서연','GUARDIAN','FEMALE','1981-04-19','서울 강서구 공항대로 247',2,0.42),
 ('dmoG04','최우진','GUARDIAN','MALE','1984-12-03','경기 김포시 김포한강로 100',2,0.55),
 ('dmoW05','정옥희','WARD','FEMALE','1945-02-27','부산 해운대구 해운대로 570',2,0.80),
 ('dmoG05','정하늘','GUARDIAN','FEMALE','1982-08-11','부산 수영구 광안해변로 219',3,0.50),
 ('dmoW06','강복례','WARD','FEMALE','1940-06-14','대구 수성구 달구벌대로 2450',4,0.35),
 ('dmoG06','강도윤','GUARDIAN','MALE','1975-10-25','대구 수성구 동대구로 100',4,0.48),
 ('dmoW07','조만석','WARD','MALE','1942-12-09','광주 북구 용봉로 77',4,0.75),
 ('dmoG07','조수아','GUARDIAN','FEMALE','1983-03-30','광주 서구 상무중앙로 61',5,0.40),
 ('dmoW08','윤금자','WARD','FEMALE','1947-07-17','대전 서구 둔산로 100',5,0.62),
 ('dmoW09','장현수','WARD','MALE','1944-01-05','경기 수원시 팔달구 효원로 241',6,0.30),
 ('dmoG08','장예린','GUARDIAN','FEMALE','1980-09-22','경기 수원시 영통구 광교중앙로 140',6,0.50),
 ('dmoW10','임춘자','WARD','FEMALE','1939-04-02','강원 춘천시 중앙로 1',6,0.72),
 ('dmoW11','한덕수','WARD','MALE','1945-08-15','서울 은평구 통일로 684',12,0.50),
 ('dmoW12','오분이','WARD','FEMALE','1942-10-10','경기 성남시 분당구 정자일로 95',18,0.50),
 ('dmoG09','한지우','GUARDIAN','MALE','1978-02-11','서울 마포구 월드컵북로 400',13,0.50),
 ('dmoG10','오태현','GUARDIAN','MALE','1977-05-06','경기 성남시 분당구 판교역로 166',19,0.50),
 ('dmoG11','한소희','GUARDIAN','FEMALE','1985-11-27','서울 서대문구 연세로 50',25,0.50),
 ('dmoG12','이재훈','GUARDIAN','MALE','1980-06-18','경기 고양시 덕양구 고양대로 1955',33,0.50);

-- 오늘 가입은 자정~지금 사이, 지난날은 그날 frac 지점
INSERT INTO users(id,email,password,name,phone,role,status,provider,last_login_at,created_at,updated_at,address,address_detail,gender,birth_date)
SELECT du.id, lower(du.id) || '@demo.silverbridge.test', NULL, du.name, NULL, du.role, 'ACTIVE', 'LOCAL',
       c.ts + interval '20 minutes', c.ts, c.ts, du.addr, '', du.gender, du.birth
FROM du, t,
LATERAL (SELECT CASE WHEN du.days_ago = 0 THEN t.d0 + (t.n - t.d0) * du.frac
                     ELSE t.d0 - du.days_ago * interval '1 day' + du.frac * interval '1 day' END AS ts) c;

-- 재촉(FCM) 시도 자체를 막아 알림 이력에 가짜 실패가 쌓이지 않게 한다
INSERT INTO guardian_anomaly_setting(guardian_id, review_reminder_enabled)
SELECT id, false FROM du WHERE role = 'GUARDIAN';

-- 2) 연결: ACTIVE 13건(피보호자 1명은 보호자 없음), PENDING 3건(1건은 9일 묵음 → 사각지대 지표)
CREATE TEMP TABLE dc(g text, w text, status text, rel text, created timestamptz);
INSERT INTO dc
SELECT g, w, s, r, greatest(gu.created_at, wu.created_at) + interval '30 minutes'
FROM (VALUES ('dmoG01','dmoW01','ACTIVE','아들'),('dmoG02','dmoW03','ACTIVE','딸'),('dmoG03','dmoW04','ACTIVE','딸'),
             ('dmoG04','dmoW04','ACTIVE','아들'),('dmoG05','dmoW05','ACTIVE','딸'),('dmoG06','dmoW06','ACTIVE','아들'),
             ('dmoG07','dmoW07','ACTIVE','딸'),('dmoG08','dmoW09','ACTIVE','딸'),('dmoG09','dmoW11','ACTIVE','아들'),
             ('dmoG10','dmoW12','ACTIVE','아들'),('dmoG11','dmoW11','ACTIVE','손녀'),('dmoG12','dmoW02','ACTIVE','아들'),
             ('dmoG06','dmoW08','ACTIVE','조카'),('dmoG05','dmoW10','PENDING','손녀'),('dmoG07','dmoW06','PENDING','딸'))
     v(g,w,s,r)
JOIN users gu ON gu.id = v.g JOIN users wu ON wu.id = v.w;
-- 오늘 만든 사람이 낀 연결이 미래 시각이 되지 않게
UPDATE dc SET created = least(created, (SELECT n FROM t) - interval '5 minutes');
INSERT INTO dc VALUES ('dmoG10','dmoW11','PENDING','사위', (SELECT n FROM t) - interval '9 days');

INSERT INTO connection(guardian_id, ward_id, status, initiated_by, connected_at, created_at, updated_at, relation)
SELECT g, w, status, g, CASE WHEN status='ACTIVE' THEN created + interval '2 hours' END, created,
       CASE WHEN status='ACTIVE' THEN created + interval '2 hours' ELSE created END, rel
FROM dc;
-- 수락 시각이 지금을 넘지 않게
UPDATE connection SET connected_at = least(connected_at, now() - interval '1 minute'), updated_at = least(updated_at, now() - interval '1 minute')
WHERE guardian_id LIKE 'dmo%';

-- 3) 이상감지 상황: 오늘 8건 + 지난 6일 14건
CREATE TEMP TABLE di(w text, type text, review text, conf float8, events int, day_ago int, frac numeric, fb text[]);
INSERT INTO di VALUES
 ('dmoW01','FIRE','REAL',0.82,4,0,0.12,ARRAY['dmoG01:REAL']),
 ('dmoW04','FIRE','CONFLICTED',0.67,3,0,0.24,ARRAY['dmoG03:REAL','dmoG04:FALSE_ALARM']),
 ('dmoW05','FIRE','REAL',0.71,2,0,0.36,ARRAY['dmoG05:REAL']),
 ('dmoW09','FIRE','REAL',0.77,5,0,0.48,ARRAY['dmoG08:REAL']),
 ('dmoW06','FALL','REAL',0.74,2,0,0.58,ARRAY['dmoG06:REAL']),
 ('dmoW11','WEAPON','FALSE_ALARM',0.58,1,0,0.68,ARRAY['dmoG09:FALSE_ALARM','dmoG11:FALSE_ALARM']),
 ('dmoW03','FALL','REAL',0.68,1,0,0.78,ARRAY['dmoG02:REAL']),
 ('dmoW12','FIRE','PENDING',0.63,2,0,0.90,ARRAY[]::text[]),
 ('dmoW11','FIRE','REAL',0.79,3,1,0.40,ARRAY['dmoG09:REAL','dmoG11:REAL']),
 ('dmoW12','FALL','FALSE_ALARM',0.52,1,1,0.65,ARRAY['dmoG10:FALSE_ALARM']),
 ('dmoW04','FIRE','REAL',0.86,4,2,0.55,ARRAY['dmoG03:REAL','dmoG04:REAL']),
 ('dmoW12','FIRE','FALSE_ALARM',0.49,1,2,0.80,ARRAY['dmoG10:FALSE_ALARM']),
 ('dmoW11','WEAPON','REAL',0.71,2,3,0.45,ARRAY['dmoG09:REAL']),
 ('dmoW12','FIRE','REAL',0.69,2,3,0.70,ARRAY['dmoG10:REAL']),
 ('dmoW11','FALL','FALSE_ALARM',0.54,1,4,0.35,ARRAY['dmoG11:FALSE_ALARM']),
 ('dmoW12','FIRE','FALSE_ALARM',0.47,1,4,0.60,ARRAY['dmoG10:FALSE_ALARM']),
 ('dmoW11','FIRE','REAL',0.88,6,4,0.85,ARRAY['dmoG09:REAL','dmoG11:REAL']),
 ('dmoW12','FALL','REAL',0.66,2,5,0.30,ARRAY['dmoG10:REAL']),
 ('dmoW11','FIRE','FALSE_ALARM',0.50,1,5,0.55,ARRAY['dmoG09:FALSE_ALARM']),
 ('dmoW12','FIRE','REAL',0.81,3,5,0.75,ARRAY['dmoG10:REAL']),
 ('dmoW11','FIRE','FALSE_ALARM',0.53,2,6,0.45,ARRAY['dmoG11:FALSE_ALARM']),
 ('dmoW12','WEAPON','FALSE_ALARM',0.56,1,6,0.70,ARRAY['dmoG10:FALSE_ALARM']);

ALTER TABLE di ADD COLUMN started timestamptz;
-- 오늘 건은 자정 ~ (지금 - 75분) 사이에 펼친다(재촉 기준을 넘긴 "닫힌 상황"처럼 보이게, 판정 시각이 미래가 되지 않게)
UPDATE di SET started = CASE WHEN day_ago = 0
    THEN (SELECT d0 + greatest(n - d0 - interval '75 minutes', interval '10 minutes') * frac FROM t)
    ELSE (SELECT d0 - day_ago * interval '1 day' + frac * interval '1 day' FROM t) END;

ALTER TABLE di ADD COLUMN incident_id bigint;
WITH ins AS (
  INSERT INTO anomaly_incident(ward_id, session_id, detected_type, started_at, last_detected_at, event_count, max_confidence, review_status, created_at, updated_at)
  SELECT w, 'ward_DemoSeedWard' || right(w,2) || 'Aa', type, started, started + (events - 1) * interval '1 minute', events, conf, review,
         started, started + (events - 1) * interval '1 minute' + interval '15 minutes'
  FROM di ORDER BY started
  RETURNING id, ward_id, detected_type, started_at
)
UPDATE di SET incident_id = ins.id FROM ins
WHERE ins.ward_id = di.w AND ins.detected_type = di.type AND ins.started_at = di.started;

-- 감지 이력(1분 간격, 마지막이 최대 신뢰도)
INSERT INTO anomaly_event(ward_id, session_id, detected_type, confidence, danger, detected_at, created_at, incident_id)
SELECT w, 'ward_DemoSeedWard' || right(w,2) || 'Aa', type,
       round((conf - (events - k) * 0.03)::numeric, 4)::float8, true,
       started + (k - 1) * interval '1 minute', started + (k - 1) * interval '1 minute', incident_id
FROM di, generate_series(1, di.events) k;

-- 보호자 응답(판정 시각 = 마지막 감지 + 15분, 지금을 넘지 않게)
INSERT INTO anomaly_incident_feedback(incident_id, guardian_id, verdict, created_at, updated_at)
SELECT di.incident_id, split_part(f,':',1), split_part(f,':',2),
       least(di.started + (di.events - 1) * interval '1 minute' + interval '15 minutes', now()),
       least(di.started + (di.events - 1) * interval '1 minute' + interval '15 minutes', now())
FROM di, unnest(di.fb) f;

-- 4) 알림 이력: 오늘 이상감지 상황마다 보호자(FCM+알림톡)·본인(FCM) 전달 기록
INSERT INTO notification_log(created_at, type, recipient_id, ward_id, title, body, result, channel_results)
SELECT di.started + interval '3 seconds', 'ANOMALY_DETECTED', c.guardian_id, di.w,
       CASE di.type WHEN 'FIRE' THEN '화재 감지' WHEN 'FALL' THEN '낙상 감지' ELSE '흉기 감지' END,
       u.name || '님 댁에서 ' || CASE di.type WHEN 'FIRE' THEN '화재가' WHEN 'FALL' THEN '낙상이' ELSE '흉기가' END || ' 감지되었습니다. 앱에서 확인해 주세요.',
       'DELIVERED',
       '[{"reason": null, "status": "DELIVERED", "channel": "FCM"}, {"reason": null, "status": "DELIVERED", "channel": "KAKAO_ALIMTALK"}]'::jsonb
FROM di JOIN connection c ON c.ward_id = di.w AND c.status = 'ACTIVE' JOIN users u ON u.id = di.w
WHERE di.day_ago = 0;
INSERT INTO notification_log(created_at, type, recipient_id, ward_id, title, body, result, channel_results)
SELECT di.started + interval '3 seconds', 'ANOMALY_DETECTED_SELF', di.w, di.w, '실버브릿지',
       CASE di.type WHEN 'FIRE' THEN '화재가 감지되었습니다. 안전한 곳으로 대피해 주세요.'
                    WHEN 'FALL' THEN '낙상이 감지되었습니다. 도움이 필요하면 보호자에게 연락해 주세요.'
                    ELSE '흉기가 감지되었습니다. 안전한 곳으로 피하고 112에 연락해 주세요.' END,
       'DELIVERED', '[{"reason": null, "status": "DELIVERED", "channel": "FCM"}]'::jsonb
FROM di WHERE di.day_ago = 0;

-- 5) 문의 6건(대기 3: 26시간 전 1·오늘 2 / 답변 완료 3)
INSERT INTO inquiry(user_id, category, title, content, status, answer, answered_by, answered_at, created_at, updated_at)
SELECT v.u, v.cat, v.title, v.body, v.st, v.ans,
       CASE WHEN v.st='ANSWERED' THEN (SELECT id FROM users WHERE role='ADMIN' ORDER BY created_at LIMIT 1) END,
       CASE WHEN v.st='ANSWERED' THEN v.ts + interval '3 hours' END, v.ts,
       CASE WHEN v.st='ANSWERED' THEN v.ts + interval '3 hours' ELSE v.ts END
FROM (VALUES
 ('dmoG03','ANOMALY','화재 알림이 연달아 와요','어머니 댁 주방에서 요리할 때 화재 알림이 오는데, 실제로는 불이 아니었어요. 오탐으로 표시하면 다음엔 덜 오나요?','WAITING',NULL,(SELECT n FROM t) - interval '26 hours'),
 ('dmoG05','ACCOUNT','보호자를 한 명 더 추가하고 싶어요','동생도 어머니 상태를 같이 보고 싶어하는데 보호자를 두 명 등록할 수 있나요?','WAITING',NULL,(SELECT d0 + (n - d0) * 0.35 FROM t)),
 ('dmoW07','SERVICE','글씨를 더 크게 볼 수 있나요','화면 글씨가 작아서 잘 안 보입니다. 크게 하는 방법을 알려주세요.','WAITING',NULL,(SELECT d0 + (n - d0) * 0.8 FROM t)),
 ('dmoG01','SERVICE','복약 알림 시간을 바꾸고 싶어요','아버지 저녁 약 시간을 7시에서 8시로 바꾸려면 어디서 하나요?','ANSWERED','보호자 화면 > 복약 관리에서 해당 약을 눌러 복용 시각을 수정하실 수 있습니다.',(SELECT n FROM t) - interval '3 days'),
 ('dmoG08','ANOMALY','카메라 위치는 어디가 좋을까요','거실과 주방 중 어디에 두는 게 화재 감지에 더 좋은지 궁금합니다.','ANSWERED','화재 감지는 주방이 가장 효과적입니다. 가스레인지가 화면에 들어오도록 설치해 주세요.',(SELECT n FROM t) - interval '5 days'),
 ('dmoG12','HOSPITAL','근처 제휴 병원이 있나요','고양시 덕양구 근처에 제휴 병원이 있는지 알고 싶습니다.','ANSWERED','현재 고양시에는 제휴 병원 2곳이 있습니다. 앱의 병원 찾기에서 확인하실 수 있습니다.',(SELECT n FROM t) - interval '6 days')
) v(u,cat,title,body,st,ans,ts);

-- 6) 안부 말벗: 오늘 지난 시간대 질문 안내 + 대화(지난 6일은 09·18시), 원문(companion_turn)은 넣지 않는다.
--    대화 끝 이벤트가 없으므로 주의·위험 알림은 나가지 않는다. 정각 안내 스케줄러는 COMPANION_PROMPT_WARD_IDS가 비어 있으면 아무에게도 보내지 않는다.
--    오늘 대화 없는 어르신 = W08(어제까지 대화)·W10(3일 전까지), 걱정·우울 이어짐 = W06(걱정 3일)·W12(우울 4일).
CREATE TEMP TABLE cslot AS
SELECT * FROM (
  SELECT u.id AS ward, u.created_at, dd, s.slot,
         t.d0 - dd * interval '1 day' + s.slot::time AS slot_at,
         t.d0 - dd * interval '1 day' + s.slot::time + ((ascii(right(u.id,1)) % 15) + 3) * interval '1 minute' AS st
  FROM users u, t, generate_series(0,6) dd, unnest(ARRAY['09:00','12:00','15:00','18:00','21:00']) s(slot)
  WHERE u.email LIKE '%@demo.silverbridge.test' AND u.role = 'WARD'
    AND (dd = 0 OR s.slot IN ('09:00','18:00'))
) x
WHERE x.slot_at > x.created_at + interval '10 minutes'
  AND x.st + interval '5 minutes' < (SELECT n FROM t);

INSERT INTO companion_prompt_log(ward_id, prompt_date, slot, sent_at)
SELECT ward, (slot_at AT TIME ZONE 'Asia/Seoul')::date, slot, slot_at FROM cslot WHERE dd = 0;

INSERT INTO companion_session(ward_id, session_date, slot, question_id, max_turns, status, end_reason, mood,
                              turn_count, answered_count, no_response_count, risk_flag, alert_level, prompt_version,
                              started_at, last_activity_at, ended_at, is_test, max_risk_prob)
SELECT c.ward, (c.slot_at AT TIME ZONE 'Asia/Seoul')::date, c.slot, q.id, 8, 'FINISHED',
       CASE WHEN (ascii(right(c.ward,1)) + c.dd) % 4 = 0 THEN 'USER_STOP' ELSE 'MAX_TURNS' END,
       CASE
         WHEN c.ward = 'dmoW06' AND c.dd <= 2 THEN 'WORRY'
         WHEN c.ward = 'dmoW06' AND c.dd = 3 THEN 'CALM'
         WHEN c.ward = 'dmoW12' AND c.dd <= 3 THEN 'DEPRESSED'
         WHEN c.ward = 'dmoW12' AND c.dd = 4 THEN 'JOY'
         WHEN c.dd = 0 THEN CASE WHEN ascii(right(c.ward,1)) % 2 = 0 THEN 'CALM' ELSE 'JOY' END
         ELSE (ARRAY['JOY','CALM','CALM','JOY','WORRY','CALM'])[1 + (ascii(right(c.ward,1)) + c.dd) % 6]
       END,
       tc, tc, 0, false, 'INFO', '{"reply":"reply-v2","signals":"signals-v1"}',
       c.st, c.st + tc * interval '30 seconds', c.st + tc * interval '30 seconds', false,
       round((0.04 + ((ascii(right(c.ward,1)) * 7 + c.dd * 3) % 25) / 100.0)::numeric, 4)
FROM cslot c
CROSS JOIN LATERAL (SELECT CASE WHEN (ascii(right(c.ward,1)) + c.dd) % 4 = 0 THEN 5 ELSE 8 END AS tc) k
CROSS JOIN LATERAL (
  SELECT id FROM companion_question WHERE active AND slot = c.slot ORDER BY id
  OFFSET ((extract(epoch FROM c.slot_at)::bigint / 86400) % greatest((SELECT count(*) FROM companion_question WHERE active AND slot = c.slot), 1))
  LIMIT 1) q
WHERE NOT (c.ward IN ('dmoW08','dmoW10') AND c.dd = 0)
  AND NOT (c.ward = 'dmoW10' AND c.dd < 3);

-- 주의 알림 기록(오늘 대화가 있는 경우만) + 보호자 알림 이력
CREATE TEMP TABLE cn AS
SELECT v.w, v.mood, v.days, min(s.ended_at) + interval '1 minute' AS sent
FROM (VALUES ('dmoW06','WORRY',3),('dmoW12','DEPRESSED',4)) v(w,mood,days)
JOIN companion_session s ON s.ward_id = v.w AND s.session_date = (SELECT (n AT TIME ZONE 'Asia/Seoul')::date FROM t)
GROUP BY v.w, v.mood, v.days;
INSERT INTO companion_notice_log(ward_id, notice_date, mood, days, sent_at)
SELECT w, (sent AT TIME ZONE 'Asia/Seoul')::date, mood, days, least(sent, now()) FROM cn;
INSERT INTO notification_log(created_at, type, recipient_id, ward_id, title, body, result, channel_results)
SELECT least(cn.sent, now()), 'COMPANION_NOTICE', c.guardian_id, cn.w, '안부 말벗',
       u.name || '님이 걱정스럽거나 가라앉은 기분이 ' || cn.days || '일째예요. 안부를 여쭤 주세요.',
       'DELIVERED', '[{"reason": null, "status": "DELIVERED", "channel": "FCM"}]'::jsonb
FROM cn JOIN connection c ON c.ward_id = cn.w AND c.status = 'ACTIVE' JOIN users u ON u.id = cn.w;

-- 7) 공지 임시저장 1건
INSERT INTO announcement_draft(author_id, title, content, created_at, updated_at)
SELECT (SELECT id FROM users WHERE role='ADMIN' ORDER BY created_at LIMIT 1), '[데모] 12월 정기 점검 안내',
       '12월 3일(수) 새벽 2시~4시에 서비스 점검이 있습니다. 점검 중에도 SOS와 화재 알림은 정상 동작합니다.', now() - interval '2 hours', now() - interval '2 hours';

COMMIT;
