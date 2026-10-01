# Design Decisions

## Order와 Fill 분리

응답이 불명확한 요청을 체결로 표시하면 조회와 적용이 실제 확인 수준을 넘어섭니다. [OrderIntent](../src/main/java/com/codewus/showcase/order/model/OrderIntent.java)는 `INTENDED`, `UNCERTAIN`, `CONFIRMED`를 표현하고 [FillRecord](../src/main/java/com/codewus/showcase/order/model/FillRecord.java)는 확인된 aggregate 결과만 표현합니다. `FillRecorder.validate`는 clientOrderId·symbol·side·quantity가 요청과 같은지 확인합니다. [V2](../src/main/resources/db/migration/V2__order_fill_event.sql)와 `permanentlyUnknownResultRemainsProtectedAcrossRetries` ([PostgresReliabilityTest](../src/postgresTest/java/com/codewus/showcase/PostgresReliabilityTest.java))에서 분리를 확인할 수 있습니다.

## 응답 유실 시 재전송보다 재조회

응답을 못 받았다는 사실만으로 외부 실행 여부를 알 수 없습니다. [OrderService.submit](../src/main/java/com/codewus/showcase/order/OrderService.java)은 응답 유실 후 같은 clientOrderId로 query합니다. 이미 claim이 있는 실행자는 submit을 호출하지 않습니다. query가 불명확해도 claim은 유지하며 새로운 제출을 자동으로 시도하지 않습니다. [OrderMapper SQL](../src/main/resources/mapper/OrderMapper.xml)과 `lostResponseQueriesSameClientOrderId`, `claimLoserOnlyQueriesEvenIfOwnerHasNotSubmitted` ([OrderPolicyTest](../src/test/java/com/codewus/showcase/OrderPolicyTest.java))가 확인합니다.

claim을 만료시키지 않는 선택은 중복 가능성을 줄이는 대신, claim commit 뒤 submit 전 중단된 주문의 진행을 포기합니다. 전체 exactly-once 보장이나 자동 완료 보장으로 해석할 수 없습니다.

## 논리 ID와 요청 fingerprint

논리 주문 범위는 `(actorId, actionId)`입니다. `clientOrderId`는 이 둘의 SHA-256 일부로 생성하고, fingerprint는 symbol·side·정규화한 quantity까지 포함한 전체 SHA-256입니다. quantity `1`, `1.0`은 같은 요청입니다. 입력 식별자에서 구분자를 허용하지 않아 canonical 문자열의 모호성을 줄입니다. 같은 action의 다른 유효 payload에는 HTTP 409를 반환하고, 정의되지 않은 JSON 필드는 HTTP 400으로 거절합니다. hash 충돌 가능성을 0으로 주장하지 않으며, DB의 clientOrderId unique와 저장 action 확인으로 충돌 시 진행을 중단합니다. [OrderCommand](../src/main/java/com/codewus/showcase/order/model/OrderCommand.java) → [주문 SQL](../src/main/resources/mapper/OrderMapper.xml) → [요청 충돌 테스트](../src/test/java/com/codewus/showcase/OrderPolicyTest.java)를 참고합니다.

주문 INSERT와 claim INSERT는 `ON CONFLICT DO NOTHING`으로 모든 unique 충돌을 처리합니다. 이후 action과 fingerprint를 확인해 동일 요청인지 판별합니다. 동시 INSERT에서 특정 conflict target 외의 unique index도 충돌할 수 있기 때문입니다. `concurrentLogicalSubmissionsCallExchangeOnce`가 실제 PostgreSQL에서 이를 검증합니다.

## 기존 거래 기록 유지와 조회 scope

신규 구조가 모든 과거 거래를 포함한다고 가정하면 조회에서 과거 기록이 빠집니다. 신규 결과는 legacy와 Fill에 함께 기록하고, 기존 legacy만 있는 데이터는 그대로 둡니다. 신규 Fill에 나타난 거래만 기존 목록에서 제외합니다.

- `actorId`: 합성 참여자의 데이터 구분. 로그인 사용자나 실계좌가 아닙니다.
- `exchangeOrderId`: `(actorId, symbol, exchangeOrderId)` 범위에서 같은 거래입니다. 다른 symbol·actor의 같은 ID는 별개입니다.
- `legacyTradeId`: DB 전체의 numeric PK이며, 연결과 dedup에는 actor·symbol도 함께 확인합니다. Fill의 복합 FK가 다른 scope의 연결을 막습니다.
- 정렬: occurredAt 내림차순 → 동률이면 NEW 우선 → 해당 source의 numeric ID 내림차순. merge/dedup 후 limit을 적용합니다.

[OrderHistoryService](../src/main/java/com/codewus/showcase/order/OrderHistoryService.java), [V2 복합 FK](../src/main/resources/db/migration/V2__order_fill_event.sql), `compatibilityHistoryPrefersNewAndScopesIdentityBeforeSortingLimit` ([PostgresReliabilityTest](../src/postgresTest/java/com/codewus/showcase/PostgresReliabilityTest.java))가 이 범위를 확인합니다. 대량 데이터에 적합한 페이지 조회 구현은 아닙니다.

## unique constraint와 transaction의 역할

unique는 같은 identity의 두 번째 저장을 막습니다. transaction은 서로 연관된 상태와 marker가 일부만 저장되는 것을 막습니다. 어느 하나가 다른 하나를 대체하지 않습니다. `fill(order_id)`, `fill(actor_id,symbol,exchange_order_id)`, `event(fill_id,type)`, `application_ledger(fill_id)`를 unique로 제한했습니다. [FillApplicationService](../src/main/java/com/codewus/showcase/order/FillApplicationService.java)는 local_state row를 잠그고, 적용 여부를 확인한 뒤 상태와 ledger를 같은 transaction에 저장합니다. [적용 SQL](../src/main/resources/mapper/ApplicationMapper.xml), `realUniqueConstraintsRejectDuplicateFillEventAndLedger`, `localFailureRollsBackBothStateAndApplicationMarker`, `concurrentRecoveryAppliesOnce`가 이를 검증합니다.

## DB lock 밖에서 외부 호출

외부 호출 시간 동안 DB transaction과 lock을 유지해도 거래소와 DB가 하나의 원자적 transaction이 되지는 않습니다. [OrderService](../src/main/java/com/codewus/showcase/order/OrderService.java)는 짧은 transaction으로 claim을 확정한 뒤 Fake Exchange를 호출합니다. claim 이후 중단된 경우에는 query만 허용합니다. [V3](../src/main/resources/db/migration/V3__claim_and_local_application.sql)와 `exchangeSubmitAndQueryRunOutsideDatabaseTransaction` ([PostgresReliabilityTest](../src/postgresTest/java/com/codewus/showcase/PostgresReliabilityTest.java))가 DB transaction 밖의 호출을 확인합니다.

## Event를 독립적으로 복구

Event 저장 실패 때문에 확인된 Fill을 잃지 않도록 Fill을 먼저 commit하고, 각 Event는 별도 transaction으로 저장합니다. eventCount가 2보다 작으면 후속 이력이 빠져 있습니다. [FillRecorder.repairEvents](../src/main/java/com/codewus/showcase/order/FillRecorder.java)는 이미 있는 type을 건너뛰며, 동시 저장은 unique로 보호합니다. [Event SQL](../src/main/resources/mapper/EventMapper.xml)과 `eventSqlFailurePreservesCommittedFillAndRetriesOnlyMissingEvent`가 Fill 보존과 누락 건만의 재처리를 검증합니다. Event는 재구성의 원천이나 메시지 전달 큐가 아닙니다.
