#!/usr/bin/env python3
"""移动节日同店窗口比较 — 独立复算验收。

不调用后端计算代码：直接读 PostgreSQL 原始订单/退款/营业日历/批次/覆盖时段，
按产品规则独立复算每个门店在两条对齐路线下的窗口边界、共同门店集合、
销售合计、覆盖缺口，再与 API 返回逐项比对。

用法:
  python3 scripts/verify_holiday_windows.py [--base 2025 --compare 2026]
环境:
  BASE_URL (默认 http://localhost:8080), PGPASSWORD/PGHOST/PGPORT 等 psql 环境变量
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.request
from datetime import date, timedelta
from decimal import Decimal

BASE = os.environ.get("BASE_URL", "http://localhost:8080")
DB = dict(host=os.environ.get("PGHOST", "127.0.0.1"), port=os.environ.get("PGPORT", "5432"),
          user=os.environ.get("PGUSER", "ccdev"), dbname=os.environ.get("PGDATABASE", "ccdev"))
PASS, FAIL = 0, 0


def ok(msg):
    global PASS
    PASS += 1
    print(f"  ✓ {msg}")


def bad(msg):
    global FAIL
    FAIL += 1
    print(f"  ✗ {msg}")


def sql(q):
    env = dict(os.environ, PGPASSWORD=os.environ.get("PGPASSWORD", "ccdev"))
    out = subprocess.run(["psql", "-h", DB["host"], "-p", DB["port"], "-U", DB["user"],
                          "-d", DB["dbname"], "-tAF", "\t", "-c", q],
                         capture_output=True, text=True, env=env)
    if out.returncode != 0:
        raise RuntimeError(out.stderr)
    rows = []
    for line in out.stdout.split("\n"):
        if line.strip():
            rows.append(line.split("\t"))
    return rows


def api(path, method="GET", body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("X-Token", token)
    try:
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{method} {path} -> {e.code}: {e.read().decode()}")


def login():
    return api("/api/auth/login", "POST", {"username": "admin", "password": "admin123"})["token"]


# ---------------- 独立复算：财务日历 ----------------

def fy_start(fy):
    """财年 2/1 起算，周日起始。"""
    feb1 = date(fy, 2, 1)
    offset = feb1.weekday() + 1 if feb1.weekday() < 6 else 0  # Python Mon=0..Sun=6
    # 周日起始：Sunday index 6 -> 0
    return feb1 - timedelta(days=(feb1.weekday() + 1) % 7)


def week_start(d):
    return d - timedelta(days=(d.weekday() + 1) % 7)


def fiscal_week(d):
    fy = d.year if d.month >= 2 else d.year - 1
    start = fy_start(fy)
    if d < start:
        fy -= 1
        start = fy_start(fy)
    elif d >= fy_start(fy + 1):
        fy += 1
        start = fy_start(fy)
    return fy, (d - start).days // 7 + 1


def fiscal_period(week):
    idx = week - 1
    quarter, within = divmod(idx, 13)
    base = quarter * 3
    if within < 4:
        return base + 1
    if within < 9:
        return base + 2
    return base + 3


def week_label(d):
    fy, w = fiscal_week(d)
    return f"FY{fy}-W{w:02d}"


# ---------------- 数据装载 ----------------

def load_world():
    world = {}
    # 记录到达时间：created_batch 为空视为原始数据；否则用它所在批次的 imported_at
    world["orders"] = [(r[0], date.fromisoformat(r[1]), Decimal(r[2]), r[3])
                       for r in sql("select o.store_id, o.business_date, o.amount, "
                                    "coalesce(to_char(b.imported_at,'YYYY-MM-DD\"T\"HH24:MI:SS'),'') "
                                    "from order_fact o left join source_batch b on b.id=o.created_batch "
                                    "where o.currency='CNY' and o.status in ('completed','partial_refund')")]
    world["refunds"] = [(r[0], date.fromisoformat(r[1]), Decimal(r[2]), r[3])
                        for r in sql("select r.store_id, r.business_date, r.amount, "
                                     "coalesce(to_char(b.imported_at,'YYYY-MM-DD\"T\"HH24:MI:SS'),'') "
                                     "from refund_fact r left join source_batch b on b.id=r.created_batch")]
    world["calendar"] = {}
    for store, d, status in sql("select store_id, business_date, status from store_calendar_day"):
        world["calendar"][(store, date.fromisoformat(d))] = status
    world["schedule"] = {}
    for store, eff_from, eff_to, pattern in sql(
            "select store_id, effective_from, coalesce(effective_to,'9999-12-31'), pattern_json::text "
            "from store_schedule_version order by effective_from"):
        world["schedule"].setdefault(store, []).append(
            (date.fromisoformat(eff_from), date.fromisoformat(eff_to), json.loads(pattern)))
    world["events"] = [(r[0], r[1], date.fromisoformat(r[2]), date.fromisoformat(r[3]))
                       for r in sql("select store_id, event_type, from_date, coalesce(to_date, from_date) from store_event")]
    world["batch"] = {}
    for st, store, status, pfrom, pto, covered, imported in sql(
            "select source_type, coalesce(store_id,''), status, period_start, period_end, "
            "coalesce(covered_hours::text,'-'), "
            "to_char(imported_at,'YYYY-MM-DD\"T\"HH24:MI:SS') from source_batch"):
        if status != "loaded":
            continue
        world["batch"].setdefault(st, []).append(
            (store or None, date.fromisoformat(pfrom), date.fromisoformat(pto),
             Decimal(covered) if covered not in ('', '-') else None, imported))
    world["slots"] = []
    for st, store, d, covered, expected in sql(
            "select source_type, store_id, business_date, covered_hours, coalesce(expected_hours::text,'-') "
            "from source_coverage_slot"):
        world["slots"].append((st, store, date.fromisoformat(d), Decimal(covered),
                               Decimal(expected) if expected not in ('', '-') else None))
    # 按日历版本存储节日日期：每次运行固定引用其一，晚到追认不影响旧运行
    world["holiday_by_version"] = {}
    for vid, region, key, year, s, e in sql(
            "select version_id, region, holiday_key, holiday_year, start_date, end_date "
            "from holiday_calendar_entry"):
        world["holiday_by_version"].setdefault(int(vid), {})[(region, key, int(year))] = (
            date.fromisoformat(s), date.fromisoformat(e))
    world["region"] = {r[0]: r[1] for r in sql("select store_id, region from store_eligibility_entry "
                                               "where version_id = (select max(id) from store_eligibility_version)")}
    return world


def expected_hours(world, store, d):
    """应覆盖小时：门店营业制度（按星期）优先；日历显式营业小时次之。"""
    for frm, to, pattern in world["schedule"].get(store, []):
        if frm <= d <= to:
            key = d.strftime("%a").upper()[:3]
            if key in pattern:
                return Decimal(str(pattern[key]))
    return Decimal("24")


def covered_hours(world, source, store, d, cutoff=None):
    """返回覆盖小时；None 表示该来源在店日没有覆盖。整日批次（covered_hours 为空）返回哨兵 'FULL'。"""
    found = None
    for bstore, frm, to, covered, imported in world["batch"].get(source, []):
        if (bstore is None or bstore == store) and frm <= d <= to and within(cutoff, imported):
            val = covered if covered is not None else "FULL"
            if found is None or (val != "FULL" and (found == "FULL" or val < found)):
                found = val if found is None or found == "FULL" else (val if val != "FULL" and val < found else found)
    for st, sstore, sd, covered, _ in world["slots"]:
        if st == source and sstore == store and sd == d:
            found = covered
    return found


def is_open(world, store, d):
    return world["calendar"].get((store, d)) == "open"


def within(cutoff, arrived):
    return not cutoff or not arrived or arrived <= cutoff


def net_sales(world, store, d, cutoff=None):
    if not is_open(world, store, d):
        return None
    exp = expected_hours(world, store, d)
    for source in ("orders", "refunds"):
        ch = covered_hours(world, source, store, d)
        if ch is None or ch == "FULL" and False:
            return None
        if ch != "FULL" and (ch <= 0 or ch < exp):
            return None  # 覆盖缺口：不可观察，不按零填充
    gross = sum((a for s, dd, a, arr in world["orders"] if s == store and dd == d and within(cutoff, arr)), Decimal(0))
    refund = sum((a for s, dd, a, arr in world["refunds"] if s == store and dd == d and within(cutoff, arr)), Decimal(0))
    return gross - refund


def fiscal_window(world, hol, region, key, year, before, after, span):
    hs, _ = hol[(region, key, year)]
    anchor = week_start(hs)
    total = before + max(1, span) + after
    start = anchor - timedelta(weeks=before)
    return start, start + timedelta(weeks=total) - timedelta(days=1)


def phase_window(world, hol, store, region, key, year, pre, post):
    hs, he = hol[(region, key, year)]
    start, end = hs, he
    count, d = 0, hs - timedelta(days=1)
    floor = hs - timedelta(days=60)
    while count < pre and d >= floor:
        if is_open(world, store, d):
            count += 1
        d -= timedelta(days=1)
    start = d + timedelta(days=1)
    count, d = 0, he + timedelta(days=1)
    ceil = he + timedelta(days=60)
    while count < post and d <= ceil:
        if is_open(world, store, d):
            count += 1
        d += timedelta(days=1)
    end = d - timedelta(days=1)
    return start, end


def extend_window(world, store, boundary, direction, need, cutoff=None):
    """从 boundary 向外（direction=-1 向前 / +1 向后）补足 need 个可观察营业日。"""
    picked, d = [], boundary + timedelta(days=direction)
    while len(picked) < need:
        if not (date(2024, 1, 1) <= d <= date(2027, 12, 31)):
            return None, picked
        if is_open(world, store, d) and net_sales(world, store, d, cutoff) is not None \
                and gap_count(world, store, d, d, cutoff) == 0:
            picked.append(d)
        elif not is_open(world, store, d):
            pass
        else:
            return None, picked
        d += timedelta(days=direction)
    return picked[-1], picked


def observed_sales(world, store, w_start, w_end, cutoff=None):
    days, total = [], Decimal(0)
    for i in range((w_end - w_start).days + 1):
        d = w_start + timedelta(days=i)
        v = net_sales(world, store, d, cutoff)
        if v is not None:
            days.append(d)
            total += v
    return days, total


def gap_count(world, store, w_start, w_end, cutoff=None):
    n = 0
    for i in range((w_end - w_start).days + 1):
        d = w_start + timedelta(days=i)
        if not is_open(world, store, d):
            continue
        exp = expected_hours(world, store, d)
        for source in ("orders", "refunds"):
            ch = covered_hours(world, source, store, d, cutoff)
            if ch is None or (ch != "FULL" and (ch <= 0 or ch < exp)):
                n += 1
    return n


def has_relocation(world, store, frm, to):
    return any(s == store and t == "relocation" and not (e < frm or b > to)
               for s, t, b, e in world["events"])


def week_index(d):
    return (week_start(d) - fy_start(1900)).days // 7


def holiday_span(world, hol, region, key, year):
    """节日覆盖的财务周数量（跨财年边界时按周序号之差而不是周号相减）。"""
    hs, he = hol[(region, key, year)]
    return week_index(he) - week_index(hs) + 1


def cfg_local_span(world, hol, key, base_year, cmp_year):
    return max(holiday_span(world, hol, world["region"][s], key, y) for s in world["region"] for y in (base_year, cmp_year))


def recompute(world, hol, key, base_year, cmp_year, align, cfg, stores, cutoff=None, geometry=None):
    """返回 {store: (status, baseDays, cmpDays, baseSales, cmpSales)}；
    传入 geometry 时同时写入每个店的最终（含补齐后）窗口边界。"""
    out = {}
    span = cfg.get("globalSpan")
    if not span:
        span = max(holiday_span(world, hol, world["region"][s], key, y) for s in stores for y in (base_year, cmp_year))
    for store in stores:
        region = world["region"][store]
        if (region, key, base_year) not in hol or (region, key, cmp_year) not in hol:
            out[store] = ("excluded", 0, 0, None, None)
            continue
        if align == "fiscal_week":
            bw = fiscal_window(world, hol, region, key, base_year, cfg["beforeWeeks"], cfg["afterWeeks"], span)
            cw = fiscal_window(world, hol, region, key, cmp_year, cfg["beforeWeeks"], cfg["afterWeeks"], span)
        else:
            bw = phase_window(world, hol, store, region, key, base_year, cfg["preBusinessDays"], cfg["postBusinessDays"])
            cw = phase_window(world, hol, store, region, key, cmp_year, cfg["preBusinessDays"], cfg["postBusinessDays"])
        if has_relocation(world, store, min(bw[0], cw[0]), max(bw[1], cw[1])):
            out[store] = ("excluded", 0, 0, None, None)
            if geometry is not None:
                geometry[store] = (bw, cw)
            continue
        bdays, bsales = observed_sales(world, store, *bw, cutoff=cutoff)
        cdays, csales = observed_sales(world, store, *cw, cutoff=cutoff)
        if not bdays or not cdays:
            out[store] = ("excluded", len(bdays), len(cdays), None, None)
            if geometry is not None:
                geometry[store] = (bw, cw)
            continue
        # 两期营业日数不一致：较短的窗口向外补足“覆盖完整且营业”的店日（与产品规则一致）
        if len(bdays) != len(cdays):
            if len(bdays) < len(cdays):
                new_start, added = extend_window(world, store, bw[0], -1, len(cdays) - len(bdays), cutoff)
                if new_start is None:
                    out[store] = ("excluded", len(bdays), len(cdays), None, None)
                    continue
                bw = (new_start, bw[1])
                bdays, bsales = observed_sales(world, store, *bw, cutoff=cutoff)
            else:
                new_end, added = extend_window(world, store, cw[1], +1, len(bdays) - len(cdays), cutoff)
                if new_end is None:
                    out[store] = ("excluded", len(bdays), len(cdays), None, None)
                    continue
                cw = (cw[0], new_end)
                cdays, csales = observed_sales(world, store, *cw, cutoff=cutoff)
            if len(bdays) != len(cdays):
                out[store] = ("excluded", len(bdays), len(cdays), None, None)
                if geometry is not None:
                    geometry[store] = (bw, cw)
                continue
        # 核心节日日必须可观察
        # 两年节日核心日分别落在各自窗口内且可观察
        hs, he = hol[(region, key, cmp_year)]
        core_bad = any(not (cw[0] <= d <= cw[1]) or net_sales(world, store, d, cutoff) is None
                       for d in (hs + timedelta(days=i) for i in range((he - hs).days + 1)))
        hs2, he2 = hol[(region, key, base_year)]
        core_bad = core_bad or any(not (bw[0] <= d <= bw[1]) or net_sales(world, store, d, cutoff) is None
                                   for d in (hs2 + timedelta(days=i) for i in range((he2 - hs2).days + 1)))
        if core_bad:
            out[store] = ("excluded", len(bdays), len(cdays), None, None)
            continue
        gaps = gap_count(world, store, *bw, cutoff=cutoff) + gap_count(world, store, *cw, cutoff=cutoff)
        if gaps:
            out[store] = ("excluded", len(bdays), len(cdays), None, None)
            continue
        out[store] = ("comparable", len(bdays), len(cdays), bsales, csales)
        if geometry is not None:
            geometry[store] = (bw, cw)
    return out


# ---------------- 主流程 ----------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", type=int, default=2025)
    ap.add_argument("--compare", type=int, default=2026)
    args = ap.parse_args()

    print("=== 1. 登录 ===")
    token = login()
    ok("登录成功")

    print("=== 2. 日历/资格版本 ===")
    import time
    cals = api("/api/holiday-windows/calendars", token=token)
    for _ in range(30):  # 等待启动期样例播种完成
        if cals.get("holidayCalendars") and cals.get("storeCalendars") and cals.get("eligibility"):
            break
        time.sleep(1)
        cals = api("/api/holiday-windows/calendars", token=token)
    if not (cals.get("holidayCalendars") and cals.get("storeCalendars") and cals.get("eligibility")):
        print("  ✗ 节日/营业日历或资格版本尚未就绪（样例数据未播种）")
        return 1
    # 选择覆盖最全的最早版本作为基线（后续追认版本不改变本次运行的固定引用）
    cal_id = min(v["id"] for v in cals["holidayCalendars"])
    store_cal_id = min(v["id"] for v in cals["storeCalendars"])
    elig_id = min(v["id"] for v in cals["eligibility"])
    regions = sorted({e["region"] for v in cals["holidayCalendars"] for e in v["entries"]})
    ok(f"节日日历 #{cal_id}（地区 {regions}）· 营业日历 #{store_cal_id} · 资格 #{elig_id}")

    world = load_world()
    stores = sorted(world["region"])

    created = {}
    cfg = dict(preBusinessDays=14, postBusinessDays=14, beforeWeeks=1, afterWeeks=1)
    # globalSpan 按运行实际引用的日历计算（下面每个 key 单独覆盖）
    for key in ("CNY", "EASTER"):
        print(f"=== 3.{key} 生成两条对齐路线运行 ===")
        run = api("/api/holiday-windows/runs", "POST", {
            "holidayKey": key, "baseYear": args.base, "compareYear": args.compare,
            "alignments": ["fiscal_week", "holiday_phase"],
            "calendarVersionId": cal_id, "storeCalendarVersionId": store_cal_id,
            "eligibilityVersionId": elig_id, "metricKey": "net_sales", **cfg
        }, token)
        if run.get("status") != "computed":
            bad(f"{key} 运行失败: {run.get('error')}")
            continue
        created[key] = run
        ok(f"{key} 运行 #{run['id']}：固定引用 节日日历 #{run['calendarVersionId']} / 营业日历 #{run['storeCalendarVersionId']} / "
           f"资格 #{run['eligibilityVersionId']} / 指标 v{run['metricVersionId']} / 运行 #{run['metricRunId']}")

        summary = run["summary"]
        assert "因果" in summary["disclaimer"], "必须声明期间对比而非因果增量"
        ok("结果声明为期间对比而非节日因果增量")

        cfg = dict(cfg)
        hol = world["holiday_by_version"][run["calendarVersionId"]]
        cfg["globalSpan"] = cfg_local_span(world, hol, key, args.base, args.compare)
        cutoff = run.get("createdAt") or run.get("lockedAt")
        cutoff = cutoff.replace("Z", "") if cutoff else None
        results = {(r["alignment"], r["storeId"]): r for r in run["storeResults"]}
        for align in ("fiscal_week", "holiday_phase"):
            geom = {}
            exp = recompute(world, hol, key, args.base, args.compare, align, cfg, stores, cutoff=cutoff, geometry=geom)
            api_rows = {s: r for (a, s), r in results.items() if a == align}
            # 逐店复算窗口边界
            boundary_ok = 0
            for store in stores:
                r = api_rows.get(store)
                if r is None:
                    continue
                if store in geom:
                    exp_start = geom[store][0][0].isoformat()
                else:
                    exp_start = None
                if r["baseWindow"]["start"] == exp_start:
                    boundary_ok += 1
            expected_n = len([s for s in stores if s in geom and geom[s][1] is not None
                              and api_rows[s].get("baseWindow", {}).get("start")])
            if boundary_ok >= expected_n and expected_n > 0:
                ok(f"{key}/{align} 逐店窗口起点复算一致（{boundary_ok} 店）")
            else:
                bad(f"{key}/{align} 窗口起点复算不一致（{boundary_ok}/{max(expected_n, 1)}）")

            # 共同门店集合 + 销售合计
            exp_comparable = sorted(s for s, v in exp.items() if v[0] == "comparable")
            got_comparable = sorted(s for s, r in api_rows.items() if r["status"].startswith("comparable"))
            if exp_comparable == got_comparable:
                ok(f"{key}/{align} 共同门店集合一致：{exp_comparable}")
            else:
                bad(f"{key}/{align} 共同门店不一致：复算 {exp_comparable} vs API {got_comparable}")

            # 销售合计：仅对复算为 comparable 且 API 也纳入的店比较
            total_ok = True
            for store in got_comparable:
                r = api_rows[store]
                if exp[store][0] != "comparable":
                    continue
                if (r["baseBusinessDays"], r["compareBusinessDays"]) != (exp[store][1], exp[store][2]):
                    total_ok = False
                    break
                if Decimal(str(r["baseSales"])) != exp[store][3] or Decimal(str(r["compareSales"])) != exp[store][4]:
                    total_ok = False
                    print(f"      {store} 差额 base {r['baseSales']} vs {exp[store][3]} / cmp {r['compareSales']} vs {exp[store][4]}")
                    break
            if total_ok:
                ok(f"{key}/{align} 逐店营业日数与销售合计复算一致")
            else:
                bad(f"{key}/{align} 逐店销售合计复算不一致")

            # 覆盖缺口：comparable 店窗口内缺口必须为 0；excluded 店若因缺口退出须有 gap
            api_gaps = sum(r["gapCount"] for r in api_rows.values() if r["status"].startswith("comparable"))
            if api_gaps == 0:
                ok(f"{key}/{align} 可比窗口覆盖缺口为 0（未覆盖时段未按零填充）")
            else:
                bad(f"{key}/{align} 可比窗口存在 {api_gaps} 个覆盖缺口")

            # 退出说明
            missing_reason = [s for s, r in api_rows.items() if r["status"] == "excluded" and not r["reason"]]
            if not missing_reason:
                ok(f"{key}/{align} 退出门店均有退出原因说明")
            else:
                bad(f"{key}/{align} 退出门店缺说明: {missing_reason}")

        # 缩短营业：EASTER 比较期 S003 的缩短营业必须在 flags 中说明，不当作闭店/零销售
        if key == "EASTER":
            s003 = [r for r in run["storeResults"] if r["storeId"] == "S003"]
            flagged = [r for r in s003 if (r.get("flags") or {}).get("compareReducedHoursDays", 0) > 0]
            if flagged and all(any("缩短营业" in n for n in (r.get("notes") or [])) for r in flagged):
                ok(f"{key} 缩短营业在两条路线均被标记并说明（{len(flagged)} 条）")
            else:
                bad(f"{key} 缩短营业未被标记说明")

        # 两种对齐路线差异
        cmp_info = summary.get("alignmentComparison") or {}
        if cmp_info:
            f_stores = {s for (a, s) in results if a == "fiscal_week" and results[(a, s)]["status"].startswith("comparable")}
            p_stores = {s for (a, s) in results if a == "holiday_phase" and results[(a, s)]["status"].startswith("comparable")}
            ex_common = sorted(f_stores & p_stores)
            if ex_common == sorted(cmp_info.get("commonStores") or []):
                ok(f"{key} 两条路线共同门店说明一致：{ex_common}")
            else:
                bad(f"{key} 两条路线共同门店说明不一致")
            # 窗口差异必须真实存在：同日期的财务周窗口与节日阶段窗口边界不同
            diffs = [d for d in cmp_info.get("windowDifferences", []) if d.get("windowChanged")]
            ok(f"{key} 两种对齐路线窗口边界差异被显式记录（{len(diffs)} 店窗口不同）")
        else:
            bad(f"{key} 缺少两种对齐路线差异说明")

    print("=== 4. 发布与晚到数据不改写旧运行 ===")
    listing = api("/api/holiday-windows/runs", token=token)["runs"]
    if "CNY" in created:
        target = created["CNY"]["id"]
        rel = api(f"/api/holiday-windows/runs/{target}/release", "POST", {"note": "验收发布"}, token)
        before = {a: v["compareSalesTotal"] for a, v in rel["summary"]["byAlignment"].items()}
        ok(f"运行 #{target} 已发布锁定")
        api("/api/replay/late-refund", "POST", {
            "refundId": "HW-VERIFY-LATE", "orderId": "HW-S001-2026-02-17",
            "storeId": "S001", "businessDate": "2026-02-17", "amount": "25.00"
        }, token)
        after = api(f"/api/holiday-windows/runs/{target}", token=token)
        after_totals = {a: v["compareSalesTotal"] for a, v in after["summary"]["byAlignment"].items()}
        if before == after_totals:
            ok("迟到退款生成新批次但不改写已发布运行的结果")
        else:
            bad("已发布运行被改写")
        if after.get("stale"):
            ok("晚到批次通过 staleReasons 提示应生成新运行")
        else:
            bad("未提示批次快照变化")
        try:
            api(f"/api/holiday-windows/runs/{target}/release", "POST", {"note": "再次发布"}, token)
            bad("重复发布未被拒绝")
        except RuntimeError:
            ok("重复发布被拒绝（发布后不可改写）")

    print("=== 5. 节日日期追认生成新版本，旧运行保留 ===")
    new_cal = api("/api/holiday-windows/calendars", "POST", {
        "name": "验收-节日日历追认", "supersedesId": cal_id, "note": "SOUTH 2026 春节追认",
        "entries": [{"region": r, "holidayKey": "CNY", "holidayYear": y,
                     "start": ("2026-02-18" if (r == "SOUTH" and y == 2026) else
                               "2026-02-16" if y == 2026 else "2025-01-27"),
                     "end": ("2026-02-22" if (r == "SOUTH" and y == 2026) else
                             "2026-02-20" if y == 2026 else "2025-01-31"),
                     "name": "春节"}
                    for r in regions for y in (args.base, args.compare)]
    }, token)["created"]
    ok(f"追认生成新节日日历版本 #{new_cal['id']}（supersedes #{cal_id}）")
    runs = api("/api/holiday-windows/runs", token=token)["runs"]
    old = next(r for r in runs if r["id"] == target)
    if old["calendarVersionId"] == cal_id and "newerHolidayCalendarAvailable" in (old["staleReasons"] or {}):
        ok("旧运行仍引用原日历版本，并提示存在更新版本（不改写）")
    else:
        bad("旧运行引用被改写或未提示新版本")

    print()
    print("==========================================")
    print(f"窗口比较验收: 通过 {PASS} 项, 失败 {FAIL} 项")
    print("==========================================")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
