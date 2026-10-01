# Reliability

보장 범위는 전용 PostgreSQL의 unique constraint와 transaction, 같은 프로세스에서 공유하는 Fake Exchange 상태입니다. 전체 외부 시스템과 DB 사이 exactly-once는 보장하지 않습니다.

아래 테스트 메소드들은 [OrderPolicyTest](../src/test/java/com/codewus/showcase/OrderPolicyTest.java)와 [PostgresReliabilityTest](../src/postgresTest/java/com/codewus/showcase/PostgresReliabilityTest.java)에 있습니다.

| 실패 지점 | 보장하는 것 | 보장하지 않는 것 | 복구 방식 / 코드 / 테스트 |
| --- | --- | --- | --- |
| 같은 action의 재요청 | 저장된 동일 요청·결과 재사용. 다른 payload 충돌 | 서로 다른 action이 같은 업무 의도인지 판단 | [OrderService.submit](../src/main/java/com/codewus/showcase/order/OrderService.java), `sameActionReusesFillWithoutAnotherSubmit`, `changedQuantityConflictsBeforeExchangeCall` |
| 동시 실행자 | 한 claim의 승자만 submit | 클라이언트가 별도 action으로 만드는 주문 중복 | [OrderMapper](../src/main/resources/mapper/OrderMapper.xml), [V3](../src/main/resources/db/migration/V3__claim_and_local_application.sql), `concurrentClaimElectsExactlyOneExecutor`, `concurrentLogicalSubmissionsCallExchangeOnce` |
| claim 후 dispatch 전 중단 | 후속 실행자는 query만 함 | 실제로 전송되지 않은 주문의 자동 완료 | [OrderService](../src/main/java/com/codewus/showcase/order/OrderService.java), `durableClaimBeforeDispatchCrashPreventsAutomaticResubmit` |
| 체결됐지만 응답 유실 | 같은 clientOrderId 조회로 확인된 결과만 기록 | 외부 결과가 항상 조회 가능함 | [FakeExchangeAdapter](../src/main/java/com/codewus/showcase/exchange/FakeExchangeAdapter.java), `lostResponseUsesClientIdQueryAndNeverPostsAgain` |
| query가 계속 불명확 | Fill 없이 UNCERTAIN, 자동 재전송 없음 | 즉시 성공 또는 실패 판정 | [OrderService.acceptResult](../src/main/java/com/codewus/showcase/order/OrderService.java), `permanentlyUnknownResultRemainsProtectedAcrossRetries` |
| 결과 확인 후 Fill 저장 전 중단 | 서비스 교체 후 query로 누락 Fill·legacy 조회 데이터를 기록 | Fake Exchange 상태의 JVM 전체 재시작 보존 | [RecoveryService](../src/main/java/com/codewus/showcase/order/RecoveryService.java), `missingFillIsRecoveredByQueryAfterReplacingServiceInstances` |
| 같은 aggregate 결과 반복 | 같은 Order / actor·symbol·exchangeOrderId의 Fill 중복 저장 방지 | 모든 partial execution의 보존·합산 | [FillRecorder](../src/main/java/com/codewus/showcase/order/FillRecorder.java), [V2](../src/main/resources/db/migration/V2__order_fill_event.sql), `repeatedResultAndConcurrentFillInsertProduceOneFill` |
| Fill commit 후 Event 실패 | Fill·legacy·확인 상태 보존. 이미 있는 Event 보존 | Event와 Fill의 원자적 commit, 외부 전달 | [FillRecorder.repairEvents](../src/main/java/com/codewus/showcase/order/FillRecorder.java), [EventMapper](../src/main/resources/mapper/EventMapper.xml), `eventSqlFailurePreservesCommittedFillAndRetriesOnlyMissingEvent` |
| 로컬 적용 도중 실패 | 상태와 marker가 함께 rollback. Fill은 보존 | 상태 변경과 외부 시스템 동작의 원자성 | [FillApplicationService](../src/main/java/com/codewus/showcase/order/FillApplicationService.java), [ApplicationMapper](../src/main/resources/mapper/ApplicationMapper.xml), `localFailureRollsBackBothStateAndApplicationMarker` |
| 반복 또는 동시 복구 | 이미 적용된 Fill은 상태에 다시 반영하지 않음 | DB 외부 부작용의 중복 방지 | [V3](../src/main/resources/db/migration/V3__claim_and_local_application.sql), `repeatedRecoveryAndServiceReplacementApplyOnce`, `concurrentRecoveryAppliesOnce` |
| 신규/기존 거래 중복 표현 | 신규 우선, scope별 dedup, 전체 정렬 후 limit | 전체 legacy backfill, 기존 구조 제거 | [OrderHistoryService](../src/main/java/com/codewus/showcase/order/OrderHistoryService.java), [조회 테스트](../src/test/java/com/codewus/showcase/OrderHistoryTest.java), `compatibilityHistoryPrefersNewAndScopesIdentityBeforeSortingLimit` |
| 일반 GET | 데이터 저장·적용·복구·외부 query 없음 | 조회만으로 누락 데이터를 자동 보완 | [DemoOrderController](../src/main/java/com/codewus/showcase/api/DemoOrderController.java), `realHttpGetsDoNotApplyRepairOrChangeAnyTable` |

## 사용자가 확인할 상태

`OrderView`의 `fill`, `locallyApplied`, `eventCount`는 서로 다른 완료 수준입니다. Fill이 있다고 모든 후속 처리가 끝났다는 뜻은 아닙니다. 적용 과정에서 실패하면 요청은 실패할 수 있지만, 이미 commit된 Fill을 토대로 명시적 복구를 다시 실행할 수 있습니다. Event 실패는 응답의 eventCount로 확인합니다. 원시 예외나 외부 payload를 Event에 저장하지 않습니다.

복구는 `POST /demo/recovery`로 실행합니다. `UNKNOWN`은 재조회해도 UNCERTAIN으로 남습니다. 새 action으로 우회 제출하는 것을 안전한 복구로 취급하지 않습니다. API와 PostgreSQL은 localhost 전용이며 인증·운영 복구 도구는 포함하지 않습니다.

[PostgresMigrationTest](../src/postgresTest/java/com/codewus/showcase/PostgresMigrationTest.java)는 새 DB와 V1 legacy 데이터가 있는 상태의 upgrade를 검증합니다. DB 미준비 시 테스트를 assumption으로 skip하지 않습니다. [postgresTest task](../build.gradle)는 실행 0건 또는 skip도 실패로 처리합니다.
