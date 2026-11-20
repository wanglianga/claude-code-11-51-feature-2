# 经营指标语义层与自助分析平台

面向连锁零售集团的经营指标语义层与自助分析平台。解决总部、区域、门店三方对"本月销售额""同店增长"口径不一致的问题：通过集中语义定义、版本化运行、血缘下钻，让经营会上同名指标数字一致且可解释。

## 核心能力

- **集中语义定义**：指标用受控 DSL（表达式 AST）定义，表达来源字段、过滤、分组、聚合、四则组合、其他指标引用，**不接受任意 SQL**，也不只存一段口径说明
- **定义校验**：先识别引用环路，再校验金额、数量、比例、时间粒度；不同币种未经显式换算不能相加，门店日指标不能与门店月指标逐行相乘
- **运行引擎**：固定引用定义版本；标记数据截止点与覆盖范围，**不把未到数据当成零销售**；比率上卷时重新汇总分子分母
- **同店队列**：按两个比较期共同满足的营业状态与覆盖规则确定，跨期新开、闭店、缺资料门店各自说明，不把缺失期销售额填零后加入增长率
- **移动节日同店窗口比较**：用门店本地营业日历、获批节日日期、净销售额指标运行与来源覆盖证据构造可比窗口，同时输出「财务周」与「节日阶段」两条对齐路线的窗口销售变化、可比门店集合与无法比较范围；闭店不等于零销售、未到批次不等于闭店、未覆盖时段不按零填充；结果为期间对比而非节日因果增量
- **迟到退款**：影响计算生成新运行候选，已发布报表继续引用原运行，经营负责人决定是否发布更正
- **血缘下钻**：每个结果格可展开参与记录、过滤排除及最终算式

## 技术栈

- **后端**：Spring Boot 3.5 + Java 21（源码级；容器内使用 JDK 25 运行）+ Spring Data JPA + Flyway
- **前端**：Vue 3 + Vite + Element Plus
- **数据库**：PostgreSQL 16+（本地容器为 PostgreSQL 18）
- **部署**：Docker Compose 一键启动

## 快速开始

### 方式一：Docker Compose（推荐）

```bash
docker-compose up --build
```

启动后访问：
- 前端：http://localhost
- 后端 API：http://localhost:8080/api

### 方式二：本地开发

**前置要求**：Java 21+、Node 22+、PostgreSQL 16+（库/用户/密码均为 `ccdev`）

```bash
# 1. 启动后端（自动建表 + 播种样例数据）
cd backend
mvn spring-boot:run

# 2. 启动前端（另开终端）
cd frontend
npm install
npm run dev
```

访问 http://localhost:5173（前端开发服务器，代理 API 到 8080）

> 也可以只启动后端：先把前端构建产物放入后端静态目录，再由后端托管，访问 http://localhost:8080
>
> ```bash
> cd frontend && npm run build && rm -rf ../backend/src/main/resources/static/* \
>   && cp -r dist/* ../backend/src/main/resources/static/
> cd ../backend && mvn spring-boot:run
> ```
>
> Docker Compose 模式下前端由 nginx 单独托管，无需手动拷贝。

## 测试账号

| 账号 | 密码 | 角色 |
|------|------|------|
| admin | admin123 | 指标管理员 |
| analyst | analyst123 | 分析员 |
| manager | manager123 | 经营负责人 |

## 验证方式

### 一键验证脚本

```bash
# 确保后端已启动（localhost:8080）
bash scripts/verify.sh
```

覆盖：登录、币种校验、粒度校验、缺批次覆盖、跨营业日订单、迟到退款、同店队列、**移动节日同店窗口比较（独立复算）**。

`scripts/verify.sh` 第 11 步会调用 `scripts/verify_holiday_windows.py`：它**不调用后端计算代码**，而是直接读 PostgreSQL 的订单/退款/营业日历/批次/覆盖时段，
按产品规则独立复算每个门店在两条对齐路线下的窗口边界、共同门店集合、销售合计与覆盖缺口，再与 API 返回逐项比对（37 项检查）。
也可单独执行：

```bash
python3 scripts/verify_holiday_windows.py
```

### 关键场景手动验证

#### 1. 跨营业日订单
订单 O002 于 2026-01-10 01:15 下单（S001 营业到次日 02:00），计入 1/9 营业日。
```bash
# 运行 net_sales 2026-01-09（日粒度），S001 净销售额应为 800（O001 500 + O002 300）
curl -X POST http://localhost:8080/api/runs -H "X-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"metricKey":"net_sales","metricVersionId":3,"from":"2026-01-09","to":"2026-01-09","grain":"day"}'
```

#### 2. 缺批次数据（不把未到数据当成零销售）
S003 门店 2/1-2/7 订单批次仍在补数（backfilling）。
```bash
# 运行 net_sales 2月（月粒度），S003 单元格状态应为 partial（部分覆盖）
curl -X POST http://localhost:8080/api/runs -H "X-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"metricKey":"net_sales","metricVersionId":3,"from":"2026-02-01","to":"2026-02-28","grain":"month"}'
```

#### 3. 迟到退款
```bash
# 回放迟到退款（O001 的退款 150 元，1/10 营业日）
curl -X POST http://localhost:8080/api/replay/late-refund -H "X-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"refundId":"R003","orderId":"O001","storeId":"S001","businessDate":"2026-01-10","amount":"150.00"}'
# 影响计算生成新运行候选；已发布报表继续引用原运行，经营负责人在报表中心发布更正
```

#### 4. 币种校验
```bash
# CNY 未过滤 + USD 直接相加，应报错"不同币种不能直接相加"
curl -X POST http://localhost:8080/api/metrics/validate -H "X-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"metricKey":"t","definition":"{\"op\":\"arith\",\"operator\":\"add\",\"left\":{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},\"by\":[\"store_id\"]},\"right\":{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},\"filter\":[{\"source\":\"orders\",\"field\":\"currency\",\"operator\":\"eq\",\"value\":\"USD\"}],\"by\":[\"store_id\"]}}"}'
```

#### 5. 引用环路（并发发布）
创建两个相互引用的指标草稿，先发布一个成功，再发布另一个时因最终组合成环被拒绝（409）。

## 移动节日同店窗口比较

移动节日（春节、复活节等）在不同年份落在不同公历日期，直接按月汇总会把"节前/节后"窗口错位、把缺数当成真实增长。
本产品为**同一批门店**输出两条**互不混用**的对齐路线：

| 路线 | 锚点与窗口 | 优点 | 代价 |
| --- | --- | --- | --- |
| `fiscal_week` 财务周 | 节日首个获批日所在零售财务周（2/1 起算、周日起始、4-5-4 期间），前后各 N 周 | 两期窗口周数与星期构成一致，财务期间可直接汇总、跨店易解释 | 节日若横跨周界，节日交易会被分到相邻财务周（结果中 `holidaySplitAcrossWeeks` 标记并说明） |
| `holiday_phase` 节日阶段 | 各地获批节日日期，按节前/节后各 N 个**营业日**构造相对窗口 | 更贴近节日需求阶段，两期营业日数可比 | 窗口跨越不同财务周；地方节日日期与营业日历维护成本高 |

两条路线结果分列（`alignment` 字段），**不合并成一个序列**；汇总里给出共同可比门店、各自独有门店与逐店窗口差异。

### 关键规则

- **只纳入可观察店日**：来源完整（批次 `loaded` 且覆盖小时 ≥ 应覆盖小时）且营业日历状态可解释（`open`）。
- **闭店 ≠ 零销售**：`closed / temp_closed / relocated` 的店日不参与合计、也不填零；营业日数不一致时按覆盖完整的营业日重建窗口并记录 `rebuilds`。
- **未到批次 ≠ 闭店**：`backfilling` 批次、未登记店日、部分覆盖小时均记为 `gaps`；窗口内存在缺口即退出共同样本。
- **迁址退出共同样本**：分析区间内有 `relocation` 事件的门店直接排除并说明。
- **区域日期不同不跨店拼接**：每家门店用其所在地区（`NORTH`/`SOUTH`）的获批节日日期。
- **期间对比而非因果增量**：汇总 `disclaimer` 明确声明；节日效应与促销、天气、门店动作不可分离。
- **固定引用 + 发布不可改写**：每次运行锁定营业日历版本、获批节日日历版本、门店资格版本、指标定义版本、指标运行与批次快照 hash；
  晚到批次/节日日期追认只会把旧运行标记为 `stale` 并提示新建运行，或通过 `POST /api/holiday-windows/calendars` 生成新日历版本；已发布运行重复发布返回 409。

### 样例场景（已预置）

6 家门店、`NORTH`/`SOUTH` 两个地区：
- **跨年跨周**：春节 2025/2026 在两年落在不同财务周；`SOUTH` 2026 春节横跨财务周界。
- **区域日期不同**：`NORTH` 与 `SOUTH` 的获批春节日期相差 2-3 天。
- **节前临时闭店**：`S005` 2026-02-09~02-12 临时闭店（落在节前窗口）。
- **门店周末营业制度变化**：`S001` 2026-02-01 起周日营业小时 13→8，营业日历按各自营业日重建。
- **缩短营业**：`S003` 2026-03-30~04-03 每日缩短为 8 小时（仅比较期），应覆盖小时按缩短后小时核验，结果标记 `compareReducedHoursDays` 与 `reducedHoursMismatch`。
- **迁址**：`S006` 2026-01 迁址，退出共同样本。
- **跨期新开**：`S004` 2026-02-10 开业，比较期无基准营业日。
- **缺批次**：`S003` 2026-02-01~02-07 批次补数中，窗口内不按零填充。
- **部分小时覆盖**：`S002` 2026-04-03 订单来源仅覆盖 6/13 小时（复活节前三周窗口）。
- **迟到补数**：`POST /api/replay/late-refund` 新增迟到退款并触发批次快照变化。

### API

```bash
# 0) 登录
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# 1) 查看固定引用的日历/资格版本
curl -s localhost:8080/api/holiday-windows/calendars -H "X-Token: $TOKEN"

# 2) 生成窗口比较运行（两条对齐路线，固定引用 节日日历#1 / 营业日历#1 / 资格#1 / net_sales）
curl -s -X POST localhost:8080/api/holiday-windows/runs -H "X-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"holidayKey":"CNY","baseYear":2025,"compareYear":2026,
       "alignments":["fiscal_week","holiday_phase"],
       "calendarVersionId":1,"storeCalendarVersionId":1,"eligibilityVersionId":1,
       "metricKey":"net_sales","preBusinessDays":14,"postBusinessDays":14,"beforeWeeks":1,"afterWeeks":1}'

# 3) 发布运行（发布后不可改写，修订须新建运行）
curl -s -X POST localhost:8080/api/holiday-windows/runs/1/release -H "X-Token: $TOKEN" \
  -H 'Content-Type: application/json' -d '{"note":"经营会引用"}'

# 4) 节日日期追认：生成新日历版本，旧运行仍固定引用原版本
curl -s -X POST localhost:8080/api/holiday-windows/calendars -H "X-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"节日日历 v2","supersedesId":1,"entries":[{"region":"SOUTH","holidayKey":"CNY","holidayYear":2026,
       "start":"2026-02-18","end":"2026-02-22","name":"春节（追认）"}]}'
```

前端「节日窗口比较」页面（`/holiday-windows`）可图形化创建运行、查看两条路线汇总、逐店窗口边界/营业日/销售/缺口/重建说明，并发布运行。

## 指标 DSL 说明

指标定义是 JSON 表达式树（AST），节点类型：

| 节点 | 说明 | 示例 |
|------|------|------|
| `field` | 取源字段 | `{"op":"field","source":"orders","field":"amount"}` |
| `const` | 常量 | `{"op":"const","value":0.05}` |
| `agg` | 聚合 | `{"op":"agg","measure":"sum","expr":{...},"filter":[...],"by":["store_id"]}` |
| `arith` | 四则运算 | `{"op":"arith","operator":"sub","left":{...},"right":{...}}` |
| `convert` | 币种换算 | `{"op":"convert","expr":{...},"fromCurrency":"USD","toCurrency":"CNY","rate":7.1}` |
| `ref` | 引用其他指标 | `{"op":"ref","metric":"gross_sales"}` |
| `cohort` | 同店增长 | `{"op":"cohort","base":{...},"compare":"prev_period","cohortKey":"store_id"}` |

**示例：净销售额**
```json
{
  "op": "arith", "operator": "sub",
  "left": {"op": "ref", "metric": "gross_sales"},
  "right": {"op": "ref", "metric": "refund_total"}
}
```

**校验规则**：
- 引用环路检测（DFS 三色标记）
- 金额/数量/比例类型检查
- 不同币种未经 `convert` 换算不能相加
- 门店日指标不能与门店月指标逐行相乘
- 金额与金额不能相乘（量纲错误）

## 样例数据

预置 5 家门店（S001-S005）、2026 年 1-3 月订单/退款/门店状态/节假日数据，覆盖：
- 跨营业日订单（O002，1/10 01:15 计入 1/9）
- 多商品订单（O003，3 个订单行）
- 多次部分退款（O005 → R001、R002）
- 临时闭店（S005，2/15-2/21）
- 跨期新店（S004，2/10 开业）
- 缺批次数据（S003，2/1-2/7 补数中）
- 外币订单（O004，USD，用于币种校验演示）

## 项目结构

```
├── backend/                 # Spring Boot 后端
│   ├── src/main/java/com/semantic/platform/
│   │   ├── dsl/            # 指标 DSL（表达式 AST、校验器、源注册表）
│   │   ├── engine/         # 运行引擎（求值器、覆盖、同店队列）
│   │   ├── holiday/        # 移动节日窗口比较（财务日历、窗口计算、日历版本、运行发布）
│   │   ├── metric/         # 指标服务（CRUD、发布、影响）
│   │   ├── run/            # 运行服务（执行、血缘、回放）
│   │   ├── report/         # 报表服务（发布、更正）
│   │   ├── approval/       # 审批服务
│   │   ├── source/         # 源数据与播种
│   │   └── api/            # REST 控制器
│   └── src/main/resources/
│       ├── db/migration/   # Flyway 建表脚本
│       └── static/         # 前端构建产物（后端托管）
├── frontend/               # Vue 3 前端
│   └── src/views/          # 指标目录/编辑器/运行/报表/审批/数据源/影响
├── scripts/verify.sh       # 端到端验证脚本
├── scripts/verify_holiday_windows.py  # 移动节日窗口独立复算验收
└── docker-compose.yml      # 一键部署
```

## 验证结论

本次交付实际执行过的验证：
- ✅ 后端编译通过（Spring Boot 3.5.6；容器内用 JDK 25 运行，`maven.compiler.release=21`）
- ✅ 后端单元测试 21/21 通过（DSL 8、求值器 5、移动节日窗口 8）
- ✅ 前端构建通过（Vue 3 + Vite），构建产物由后端托管（`/holiday-windows` 返回 200）
- ✅ 端到端验证脚本 11/11 通过（登录、币种校验、粒度校验、缺批次覆盖、跨营业日订单、迟到退款、同店队列、并发环路、比率上卷、移动节日窗口）
- ✅ 移动节日窗口独立复算 37/37 通过：逐店窗口边界、共同门店集合、营业日与销售合计、覆盖缺口、两种对齐路线差异、发布锁定与晚到数据不改写旧运行、节日日期追认保留旧运行
- ✅ 引用环路并发发布被拦截（cyc_a v2 发布成功，cyc_b v2 因组合成环返回 409）
- ✅ 迟到退款完整闭环（初始 1700 → 影响计算生成候选 → 报表仍引用原运行 → 发布更正后 1550）
