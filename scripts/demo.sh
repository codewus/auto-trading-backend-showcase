#!/usr/bin/env bash
set -euo pipefail
base="http://127.0.0.1:${SHOWCASE_PORT:-18080}"
actor="demo-script-$(date +%s)-$$"

post() { curl --fail-with-body --silent --show-error -H 'Content-Type: application/json' -d "$2" "$base$1"; }
order() { post /demo/orders "{\"actorId\":\"$actor\",\"actionId\":\"$1\",\"symbol\":\"DEMOUSDT\",\"side\":\"BUY\",\"quantity\":1}"; }
scenario() { post /demo/scenarios "{\"actorId\":\"$actor\",\"actionId\":\"$1\",\"scenario\":\"$2\"}" >/dev/null; }
field() { python3 -c 'import json,sys; v=json.load(sys.stdin); print(v[sys.argv[1]][sys.argv[2]])' "$1" "$2"; }
confirmed() { python3 -c 'import json,sys; v=json.load(sys.stdin); assert v["fill"] and v["locallyApplied"] and v["eventCount"]==2'; }
unknown() { python3 -c 'import json,sys; v=json.load(sys.stdin); assert v["fill"] is None and v["order"]["status"]=="UNCERTAIN"'; }

normal=$(order normal)
printf '%s' "$normal" | confirmed
printf '1. NORMAL: confirmed and applied\n'

scenario lost RESPONSE_LOST
lost=$(order lost)
printf '%s' "$lost" | confirmed
retry=$(order lost)
test "$(printf '%s' "$lost" | field fill id)" = "$(printf '%s' "$retry" | field fill id)"
printf '2. RESPONSE_LOST + same action: same stored Fill\n'

scenario repeated REPEATED_RESULT
repeated=$(order repeated)
again=$(order repeated)
test "$(printf '%s' "$repeated" | field fill id)" = "$(printf '%s' "$again" | field fill id)"
printf '3. REPEATED_RESULT: same stored Fill\n'

scenario uncertain UNKNOWN
order uncertain | unknown
order uncertain | unknown
printf '4. UNKNOWN: no confirmed Fill\n'

scenario event-gap EVENT_FAILURE
gap=$(order event-gap)
printf '%s' "$gap" | python3 -c 'import json,sys; v=json.load(sys.stdin); assert v["fill"] and v["eventCount"]==1'
id=$(printf '%s' "$gap" | field order id)
curl --fail --silent --show-error "$base/demo/orders/$id?actorId=$actor" | python3 -c 'import json,sys; assert json.load(sys.stdin)["eventCount"]==1'
post "/demo/recovery?actorId=$actor" '{}' | python3 -c 'import json,sys; rows=json.load(sys.stdin); assert all(r["eventCount"]==2 for r in rows if r["fill"])'
printf '5. GET keeps Event gap; explicit recovery repairs it\n'

curl --fail --silent --show-error "$base/demo/history?actorId=$actor&limit=20" | python3 -c 'import json,sys; rows=json.load(sys.stdin); assert len(rows)==4; print("6. History: four confirmed entries, no duplicates")'
printf 'Demo assertions passed for synthetic actor %s\n' "$actor"
