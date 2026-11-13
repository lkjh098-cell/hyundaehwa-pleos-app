# Pleos 출시 검토 기록 · 2026-11-13

이 문서는 제출 준비와 외부 승인 대상을 구분한다. APK 생성·에뮬레이터 실행은 앱 마켓 승인이나 Gleo 활성화를 뜻하지 않는다.

## Gleo 전송 경계

[공식 예제](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples)는 APP_ACTION_RESPONSE를 암시적 Broadcast로 전송한다. 그러나 [보안 가이드](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-security-guide)는 민감 데이터의 수신 대상을 명확히 제한하도록 요구한다. 현재 결과의 지역명과 답변은 위치·사용자 행동에서 파생되므로 공개 콘텐츠라고만 간주하지 않는다.

공개 예제와 [스키마 전달 문서](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/deliver-schema)에서 신뢰할 Gleo 패키지·인증서·호출자 인증 규격을 확인하지 못했다. 설치된 앱 이름만으로 OEM 승인 수신자라고 판단하지 않는다.

기본 GLEO_RECIPIENT_PACKAGE와 GLEO_RECIPIENT_SHA256은 빈 문자열이다. 이 상태에서는 Gleo 액션을 처리하거나 결과를 전송하지 않는다. OEM이 실제 배포 대상의 패키지와 현재 단일 서명 인증서 SHA256을 확인한 뒤에만 빌드 설정에 넣는다. 인증서는 공개 식별자이며 개인 키를 포함하지 않는다. package visibility가 필요한 경우 확인된 패키지만 Manifest queries에 추가한다. QUERY_ALL_PACKAGES를 요구하지 않는다.

canAcceptActions(context)는 설치된 응답 수신자의 현재 인증서 일치를 확인한다. respond()도 다시 확인하고 setPackage로만 전송한다. 비어 있음·미설치·서명 불일치·복수 서명은 모두 전송 실패다. 디버그의 무제한 민감 Broadcast 예외도 없다. 이 확인은 수신 Intent 호출자 인증이 아니다. 공개 액션은 앱 내부의 저위험 기능에 제한하고 인증된 차량 제어라고 표시하지 않는다. 새 OEM 인증 규격이 확보되면 별도 검토한다.

[스키마 문서](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/deliver-schema)에 따른 assets/schema.json 심사와 활성화 확인도 별도로 남는다. 현재 상태는 release-readiness.json에 미승인으로 기록한다.

## 제출 전 확인

[공식 제출 가이드](https://document.pleos.ai/docs/pleos-only/distribute/app-submission-guide) 기준 확인 목록:

- 개발자 정보·연락처, 국가·Connect 플랫폼·카테고리·버전·앱 설명·출시 노트.
- APK 최대 600MB, 512×512 PNG/JPEG 아이콘 최대 1MB, 규격에 맞는 IVI 스크린샷 최대 8장·각 8MB.
- 개인정보 처리와 위치 활용·권한 신고, 필요한 SDK 프로젝트 ID, IARC 등급 증빙.
- 공개 개인정보처리방침 URL. 위치 활용 시 해당 약관 URL. 고객지원 경로 최소 하나.
- 심사 노트에 순정 내비 소유, 앱 내부 시연 모드와 실제 SDK 모드 구분, Gleo 미활성 상태, 마이크·음성·오디오 우선순위 검증 범위 기재.

[개인정보 심사 기준](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-user-privacy-security)은 처리 항목·목적·보유기간·공유·거부권 고지와 명시적 동의·철회를 요구한다. 실제 운영 주체와 공개 URL은 개발자가 확정해야 한다. 이 문서를 게시된 개인정보처리방침으로 대신하지 않는다. 선택형 목적지 날씨 조회의 외부 제공 데이터, 기기 내 위치 판별, 마이크 입력, 저장 설정과 삭제 경로를 실제 최종 앱과 대조해야 한다.

## 소스에서 확인한 보호와 검증 한계

Manifest는 allowBackup=false, usesCleartextTraffic=false다. 현재 확인한 플랫폼 소스에 임의 인증서 신뢰·호스트 검증 우회·인식문 로그 코드는 발견하지 못했다. 개발용 릴레이 저장은 EncryptedSharedPreferences를 사용하지만 출시 빌드에서 기능 비활성 여부는 최종 BuildConfig와 APK로 재확인해야 한다. 최소 권한·공급자 권한 승인·런타임 동의는 별도 확인 대상이다. 서명·디버깅 비활성·SDK 취약점·실차 안전 동작은 최종 산출물 검수에서 확인한다.

검증: :core:test의 GleoRecipientTrustTest는 빈 설정·잘못된 지문·불일치·다중 서명을 거절한다. 기기에서는 빈 설정의 GET_TALK_TOPIC/SET_TALK_QUIET/ANSWER_TALK_CARD가 상태 변경·Broadcast를 만들지 않는지 확인한다. OEM 설정 후 별도 시험 빌드에서 잘못된 지문은 거절하고 정확한 수신자만 응답을 받는지 확인한다. ACTION_PARAM의 과대 길이·잘못된 타입·JSON 뒤 잔여 텍스트도 거절해야 한다. Gleo 실제 수신·음성 완료는 Broadcast 전송 성공과 구분한다.
