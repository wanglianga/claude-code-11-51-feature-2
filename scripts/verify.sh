#!/bin/bash
# 经营指标语义层与自助分析平台 — 端到端验证脚本
# 覆盖：登录、指标校验（币种/粒度/环路）、运行、覆盖范围、迟到退款、报表更正
set -e
BASE="${BASE:-http://localhost:8080}"
PASS=0
FAIL=0

ok() { PASS=$((PASS+1)); echo "  ✓ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ✗ $1"; }

echo "=== 1. 登录 ==="
TOKEN=$(curl -s -X POST "$BASE/api/auth/login" -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])" 2>/dev/null)
if [ -n "$TOKEN" ]; then ok "登录成功"; else bad "登录失败"; exit 1; fi
AUTH="X-Token: $TOKEN"

echo "=== 2. 指标列表 ==="
COUNT=$(curl -s "$BASE/api/metrics" -H "$AUTH" | python3 -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null)
[ "$COUNT" -ge 7 ] && ok "已发布指标 $COUNT 个" || bad "指标数量不足: $COUNT"

echo "=== 3. 币种校验（CNY 未过滤 + USD 应报错） ==="
RES=$(curl -s -X POST "$BASE/api/metrics/validate" -H "$AUTH" -H "Content-Type: application/json" -d '{"metricKey":"t1","definition":"{\"op\":\"arith\",\"operator\":\"add\",\"left\":{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},\"by\":[\"store_id\"]},\"right\":{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},\"filter\":[{\"source\":\"orders\",\"field\":\"currency\",\"operator\":\"eq\",\"value\":\"USD\"}],\"by\":[\"store_id\"]}}"}')
echo "$RES" | grep -q "币种" && ok "币种不匹配被拦截" || bad "币种校验未生效"

echo "=== 4. 粒度校验（日 × 月 应报错） ==="
# 先创建月粒度指标（若不存在）
curl -s -X POST "$BASE/api/metrics" -H "$AUTH" -H "Content-Type: application/json" -d '{"metricKey":"monthly_sales","name":"月销售额","grain":"month","definition":"{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},\"filter\":[{\"source\":\"orders\",\"field\":\"currency\",\"operator\":\"eq\",\"value\":\"CNY\"}],\"by\":[\"store_id\"]}"}' > /dev/null 2>&1
curl -s -X POST "$BASE/api/metrics/monthly_sales/publish" -H "$AUTH" -H "Content-Type: application/json" -d '{"version":1}' > /dev/null 2>&1
RES=$(curl -s -X POST "$BASE/api/metrics/validate" -H "$AUTH" -H "Content-Type: application/json" -d '{"metricKey":"t2","grain":"day","definition":"{\"op\":\"arith\",\"operator\":\"mul\",\"left\":{\"op\":\"ref\",\"metric\":\"net_sales\"},\"right\":{\"op\":\"ref\",\"metric\":\"monthly_sales\"}}"}')
echo "$RES" | grep -q "粒度" && ok "粒度不匹配被拦截" || bad "粒度校验未生效"

echo "=== 5. 运行净销售额（2月，验证缺批次覆盖） ==="
VID=$(curl -s "$BASE/api/metrics/net_sales/published" -H "$AUTH" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
RUN=$(curl -s -X POST "$BASE/api/runs" -H "$AUTH" -H "Content-Type: application/json" -d "{\"metricKey\":\"net_sales\",\"metricVersionId\":$VID,\"from\":\"2026-02-01\",\"to\":\"2026-02-28\",\"grain\":\"month\"}")
RUNID=$(echo "$RUN" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
S003=$(curl -s "$BASE/api/runs/$RUNID/cells" -H "$AUTH" | python3 -c "
import sys,json
for c in json.load(sys.stdin):
    if 'S003' in str(c.get('dimsJson','')):
        print(c['cellStatus']); break")
[ "$S003" = "partial" ] && ok "S003 缺批次标记为 partial（未当成零销售）" || bad "S003 覆盖标记异常: $S003"

echo "=== 6. 跨营业日订单归属 ==="
RUN9=$(curl -s -X POST "$BASE/api/runs" -H "$AUTH" -H "Content-Type: application/json" -d "{\"metricKey\":\"net_sales\",\"metricVersionId\":$VID,\"from\":\"2026-01-09\",\"to\":\"2026-01-09\",\"grain\":\"day\"}")
RUN9ID=$(echo "$RUN9" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
VAL=$(curl -s "$BASE/api/runs/$RUN9ID/cells" -H "$AUTH" | python3 -c "
import sys,json
for c in json.load(sys.stdin):
    if 'S001' in str(c.get('dimsJson','')):
        print(c['valueNum']); break")
[ "$VAL" = "800.0" ] || [ "$VAL" = "800" ] && ok "跨营业日订单 O002 计入 1/9（净销售额 800）" || bad "跨营业日归属异常: $VAL"

echo "=== 7. 迟到退款影响计算 ==="
IMPACT=$(curl -s -X POST "$BASE/api/replay/late-refund" -H "$AUTH" -H "Content-Type: application/json" -d '{"refundId":"R-VERIFY","orderId":"O001","storeId":"S001","businessDate":"2026-01-10","amount":"50.00"}')
echo "$IMPACT" | grep -q "candidates" && ok "迟到退款生成运行候选" || bad "迟到退款影响计算失败"

echo "=== 8. 同店增长队列 ==="
SVID=$(curl -s "$BASE/api/metrics/same_store_growth/published" -H "$AUTH" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
if [ -n "$SVID" ]; then
  RUNS=$(curl -s -X POST "$BASE/api/runs" -H "$AUTH" -H "Content-Type: application/json" -d "{\"metricKey\":\"same_store_growth\",\"metricVersionId\":$SVID,\"from\":\"2026-01-01\",\"to\":\"2026-03-31\",\"grain\":\"month\"}")
  RUNSID=$(echo "$RUNS" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
  EXCLUDED=$(curl -s "$BASE/api/runs/$RUNSID/cells" -H "$AUTH" | python3 -c "
import sys,json
n=0
for c in json.load(sys.stdin):
    if c['cellStatus']=='excluded': n+=1
print(n)")
  [ "$EXCLUDED" -gt 0 ] && ok "同店队列排除说明生效（$EXCLUDED 个排除单元格）" || bad "同店队列排除未生效"
fi

echo "=== 9. 引用环路（并发发布组合重检） ==="
# 创建两个相互引用的指标
curl -s -X POST "$BASE/api/metrics" -H "$AUTH" -H "Content-Type: application/json" -d '{"metricKey":"vcyc_a","name":"环A","grain":"day","definition":"{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},\"filter\":[{\"source\":\"orders\",\"field\":\"currency\",\"operator\":\"eq\",\"value\":\"CNY\"}],\"by\":[\"store_id\"]}"}' > /dev/null
curl -s -X POST "$BASE/api/metrics/vcyc_a/publish" -H "$AUTH" -H "Content-Type: application/json" -d '{"version":1}' > /dev/null
curl -s -X POST "$BASE/api/metrics" -H "$AUTH" -H "Content-Type: application/json" -d '{"metricKey":"vcyc_b","name":"环B","grain":"day","definition":"{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"refunds\",\"field\":\"amount\"},\"by\":[\"store_id\"]}"}' > /dev/null
curl -s -X POST "$BASE/api/metrics/vcyc_b/publish" -H "$AUTH" -H "Content-Type: application/json" -d '{"version":1}' > /dev/null
# 创建相互引用的新版本
curl -s -X POST "$BASE/api/metrics" -H "$AUTH" -H "Content-Type: application/json" -d '{"metricKey":"vcyc_a","name":"环A","grain":"day","definition":"{\"op\":\"arith\",\"operator\":\"add\",\"left\":{\"op\":\"ref\",\"metric\":\"vcyc_b\"},\"right\":{\"op\":\"const\",\"value\":1}}"}' > /dev/null
curl -s -X POST "$BASE/api/metrics" -H "$AUTH" -H "Content-Type: application/json" -d '{"metricKey":"vcyc_b","name":"环B","grain":"day","definition":"{\"op\":\"arith\",\"operator\":\"add\",\"left\":{\"op\":\"ref\",\"metric\":\"vcyc_a\"},\"right\":{\"op\":\"const\",\"value\":1}}"}' > /dev/null
# 先发布 vcyc_a v2（应成功）
R1=$(curl -s -X POST "$BASE/api/metrics/vcyc_a/publish" -H "$AUTH" -H "Content-Type: application/json" -d '{"version":2}')
# 再发布 vcyc_b v2（应失败，组合成环）
R2=$(curl -s -X POST "$BASE/api/metrics/vcyc_b/publish" -H "$AUTH" -H "Content-Type: application/json" -d '{"version":2}')
echo "$R2" | grep -q "环路" && ok "并发发布组合环路被拦截" || bad "环路检测未生效: $R2"

echo "=== 10. 比率上卷（分子分母重新汇总） ==="
AVID=$(curl -s "$BASE/api/metrics/aov/published" -H "$AUTH" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
if [ -n "$AVID" ]; then
  RUNA=$(curl -s -X POST "$BASE/api/runs" -H "$AUTH" -H "Content-Type: application/json" -d "{\"metricKey\":\"aov\",\"metricVersionId\":$AVID,\"from\":\"2026-01-01\",\"to\":\"2026-01-31\",\"grain\":\"month\"}")
  RUNAID=$(echo "$RUNA" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
  HASNUMDEN=$(curl -s "$BASE/api/runs/$RUNAID/cells" -H "$AUTH" | python3 -c "
import sys,json
for c in json.load(sys.stdin):
    if c['valueNum'] is not None and c['valueDen'] is not None and c['valueDen']>0:
        print('yes'); break")
  [ "$HASNUMDEN" = "yes" ] && ok "比率单元格含分子分母（上卷重新汇总）" || bad "比率分子分母缺失"
fi

echo "=== 11. 移动节日同店窗口比较（独立复算） ==="
if command -v python3 >/dev/null 2>&1 && python3 scripts/verify_holiday_windows.py >/tmp/holiday_verify.log 2>&1; then
  HW=$(grep -o "通过 [0-9]* 项" /tmp/holiday_verify.log | tail -1)
  ok "窗口比较验收通过（$HW）"
else
  bad "窗口比较验收失败，详见 /tmp/holiday_verify.log"
  tail -20 /tmp/holiday_verify.log
fi

echo ""
echo "=========================================="
echo "验证结果: 通过 $PASS 项, 失败 $FAIL 项"
echo "=========================================="
[ $FAIL -eq 0 ] && exit 0 || exit 1
