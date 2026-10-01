# Publication Checklist

이 목록은 공개 후보의 기술 검수와 공개 시 확인할 항목입니다.

- [x] Java 17 / 안정 GA Spring Boot / 새 Gradle wrapper 구성 및 checksum 확인.
- [x] unit과 실제 PostgreSQL 테스트 성공. DB 미준비 실패와 skip 금지 확인.
- [x] demo profile 기동, localhost binding과 합성 시나리오 실행 확인.
- [x] README·docs의 내부 상대 링크와 코드 읽는 순서 검증.
- [x] Order / aggregate Fill / ledger / Event 보장 범위 구분.
- [x] 신규/legacy 조회 scope·정렬·limit과 GET 순수성 검증.
- [x] Fake Exchange 외 실제 거래소 구현, credential, UI, 전략·수익률, 운영 자료 없음.
- [x] 새 합성 fixture만 사용. secret·개인 절대 경로·private repository URL 없음.
- [x] 원본 파일 전체 복사, Git 이력·remote·hidden 설정 반입 없음.
- [x] MIT 적용 범위와 Gradle wrapper/dependency 고지 확인.
- [x] [SUPPORTING_EVIDENCE](SUPPORTING_EVIDENCE.md)에 공개 코드·SQL·테스트 연결.
- [x] 사용자 검수 후 2단계 진행 승인.
- [x] fresh Git history로 main branch 공개 후 GitHub Actions와 익명 접근 확인.

공개는 기존 이력 없이 만든 initial commit으로 진행합니다. 공개 후 [verify.yml](.github/workflows/verify.yml)의 두 job과 로그인하지 않은 접근을 확인합니다. 프로필 변경은 이 작업의 범위에 포함하지 않습니다.

2026-10-01에 [최초 공개 commit의 Verify](https://github.com/codewus/auto-trading-backend-showcase/actions/runs/36799847071)가 성공했습니다. 인증 헤더와 쿠키 없이 repository, README, LICENSE, docs 및 내부 링크의 raw 파일 접근을 확인했습니다.
