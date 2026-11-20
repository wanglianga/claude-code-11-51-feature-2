<template>
  <div class="holiday-view">
    <el-alert type="warning" :closable="false" show-icon class="disclaimer">
      <template #title>期间对比，非节日因果增量</template>
      窗口净销售额变化只说明两期同店窗口的销售差异，不区分节日效应与促销、天气、门店动作等其他因素。
      两条对齐路线（财务周 / 节日阶段）分别输出，不可混成一个序列。
    </el-alert>

    <el-card class="card">
      <template #header>
        <div class="card-header">
          <span>创建窗口比较运行</span>
          <el-button size="small" @click="loadCalendars">刷新日历</el-button>
        </div>
      </template>
      <el-form :model="form" label-width="130px" label-position="left" class="run-form">
        <el-row :gutter="16">
          <el-col :span="6">
            <el-form-item label="节日">
              <el-select v-model="form.holidayKey">
                <el-option label="春节 CNY" value="CNY" />
                <el-option label="复活节 EASTER" value="EASTER" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="基准期年份">
              <el-input-number v-model="form.baseYear" :min="2019" :max="2030" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="比较期年份">
              <el-input-number v-model="form.compareYear" :min="2020" :max="2031" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="指标">
              <el-select v-model="form.metricKey">
                <el-option label="净销售额 net_sales" value="net_sales" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="6">
            <el-form-item label="获批节日日历">
              <el-select v-model="form.calendarVersionId" placeholder="选择版本">
                <el-option v-for="v in calendars.holidayCalendars" :key="v.id"
                           :label="`#${v.id} ${v.name}`" :value="v.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="门店营业日历">
              <el-select v-model="form.storeCalendarVersionId" placeholder="选择版本">
                <el-option v-for="v in calendars.storeCalendars" :key="v.id"
                           :label="`#${v.id} ${v.name}`" :value="v.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="门店资格版本">
              <el-select v-model="form.eligibilityVersionId" placeholder="选择版本">
                <el-option v-for="v in calendars.eligibility" :key="v.id"
                           :label="`#${v.id} ${v.note || '资格版本'}`" :value="v.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="对齐路线">
              <el-checkbox-group v-model="form.alignments">
                <el-checkbox label="fiscal_week">财务周</el-checkbox>
                <el-checkbox label="holiday_phase">节日阶段</el-checkbox>
              </el-checkbox-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="6">
            <el-form-item label="节前营业日数">
              <el-input-number v-model="form.preBusinessDays" :min="0" :max="60" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="节后营业日数">
              <el-input-number v-model="form.postBusinessDays" :min="0" :max="60" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="财务周前后">
              <el-input-number v-model="form.beforeWeeks" :min="0" :max="8" />
              <span class="hint"> + </span>
              <el-input-number v-model="form.afterWeeks" :min="0" :max="8" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label=" ">
              <el-button type="primary" :loading="creating" @click="createRun">生成运行</el-button>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </el-card>

    <el-card class="card">
      <template #header>
        <div class="card-header">
          <span>窗口比较运行</span>
          <el-button size="small" @click="loadRuns">刷新</el-button>
        </div>
      </template>
      <el-table :data="runs" stripe size="small" @row-click="openRun">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="holidayKey" label="节日" width="90" />
        <el-table-column label="年份" width="130">
          <template #default="{ row }">{{ row.compareYear }} vs {{ row.baseYear }}</template>
        </el-table-column>
        <el-table-column prop="alignments" label="对齐路线" width="180" />
        <el-table-column label="固定引用" width="240">
          <template #default="{ row }">
            节日日历 #{{ row.calendarVersionId }} · 营业日历 #{{ row.storeCalendarVersionId }} · 资格 #{{ row.eligibilityVersionId }}
          </template>
        </el-table-column>
        <el-table-column prop="batchSnapshotHash" label="批次快照" width="140">
          <template #default="{ row }">
            <span class="mono">{{ (row.batchSnapshotHash || '').slice(0, 10) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag :type="row.released ? 'success' : 'info'" size="small">
              {{ row.released ? '已发布' : row.status }}
            </el-tag>
            <el-tag v-if="row.stale" type="warning" size="small" class="ml4">需新运行</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <template v-if="detail">
      <el-card class="card">
        <template #header>
          <div class="card-header">
            <span>运行 #{{ detail.id }} · {{ detail.holidayKey }} {{ detail.compareYear }} vs {{ detail.baseYear }}</span>
            <div>
              <el-button v-if="!detail.released" type="success" size="small" @click="releaseRun">
                发布本运行
              </el-button>
              <el-tag v-else type="success" size="small">
                已发布 {{ formatTime(detail.released.releasedAt) }}（{{ detail.released.releasedBy }}）
              </el-tag>
            </div>
          </div>
        </template>
        <el-alert v-if="detail.stale" type="warning" :closable="false" show-icon class="stale-alert">
          <template #title>本运行引用的数据/日历已过期，但旧运行保持不变</template>
          <div v-for="(msg, key) in detail.staleReasons" :key="key">{{ msg }}</div>
          <div class="hint">{{ detail.stalenessPolicy }}</div>
        </el-alert>
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="节日日历版本">#{{ detail.calendarVersionId }}</el-descriptions-item>
          <el-descriptions-item label="门店营业日历版本">#{{ detail.storeCalendarVersionId }}</el-descriptions-item>
          <el-descriptions-item label="门店资格版本">#{{ detail.eligibilityVersionId }}</el-descriptions-item>
          <el-descriptions-item label="指标"> {{ detail.metricKey }} </el-descriptions-item>
          <el-descriptions-item label="指标定义版本">v{{ detail.metricVersionId }}</el-descriptions-item>
          <el-descriptions-item label="指标运行 ID">#{{ detail.metricRunId }}</el-descriptions-item>
          <el-descriptions-item label="批次快照" :span="3">
            <span class="mono">{{ detail.batchSnapshotHash }}</span>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card class="card">
        <template #header><span>分路线汇总</span></template>
        <el-row :gutter="16">
          <el-col v-for="al in detail.alignments" :key="al" :span="12">
            <el-card shadow="never" class="align-card">
              <template #header>
                <div class="card-header">
                  <span>{{ alignLabel(al) }}</span>
                  <el-tag size="small" :type="al === 'fiscal_week' ? 'primary' : 'success'">{{ al }}</el-tag>
                </div>
              </template>
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="可比门店数">
                  {{ summaryFor(al).comparableCount }}
                </el-descriptions-item>
                <el-descriptions-item label="退出样本数">
                  {{ summaryFor(al).excludedCount }}
                </el-descriptions-item>
                <el-descriptions-item label="基准期净销售">
                  {{ money(summaryFor(al).baseSalesTotal) }}
                </el-descriptions-item>
                <el-descriptions-item label="比较期净销售">
                  {{ money(summaryFor(al).compareSalesTotal) }}
                </el-descriptions-item>
                <el-descriptions-item label="变化额">
                  {{ money(summaryFor(al).changeAbs) }}
                </el-descriptions-item>
                <el-descriptions-item label="变化率">
                  {{ percent(summaryFor(al).changeRatio) }}
                </el-descriptions-item>
                <el-descriptions-item label="可比窗口覆盖缺口" :span="2">
                  {{ summaryFor(al).coverageGapsInComparableWindows }} 个店日
                </el-descriptions-item>
                <el-descriptions-item label="可比门店" :span="2">
                  <el-tag v-for="s in summaryFor(al).comparableStores" :key="s" size="small" class="ml4">{{ s }}</el-tag>
                </el-descriptions-item>
              </el-descriptions>
              <div class="hint note">{{ summaryFor(al).note }}</div>
              <el-table v-if="summaryFor(al).excludedStores.length" :data="summaryFor(al).excludedStores"
                        size="small" class="excluded-table">
                <el-table-column prop="storeId" label="退出门店" width="100" />
                <el-table-column prop="region" label="地区" width="90" />
                <el-table-column prop="reason" label="退出/无法比较原因" />
              </el-table>
            </el-card>
          </el-col>
        </el-row>
        <el-alert v-if="comparison" type="info" :closable="false" class="cmp-alert">
          <template #title>两条路线差异（不可混成一个序列）</template>
          <div>共同可比门店：{{ comparison.commonStores.join('、') || '无' }}</div>
          <div>仅财务周路线可比：{{ comparison.onlyInFiscalWeek.join('、') || '无' }}</div>
          <div>仅节日阶段路线可比：{{ comparison.onlyInHolidayPhase.join('、') || '无' }}</div>
          <div class="hint">{{ comparison.note }}</div>
        </el-alert>
      </el-card>

      <el-card class="card">
        <template #header><span>逐店窗口明细</span></template>
        <el-tabs v-model="activeAlign">
          <el-tab-pane v-for="al in detail.alignments" :key="al" :label="alignLabel(al)" :name="al">
            <el-table :data="resultsFor(al)" stripe size="small">
              <el-table-column prop="storeId" label="门店" width="80" />
              <el-table-column prop="region" label="地区" width="80" />
              <el-table-column label="状态" width="150">
                <template #default="{ row }">
                  <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="基准期窗口" width="190">
                <template #default="{ row }">{{ rangeText(row.baseWindow) }}</template>
              </el-table-column>
              <el-table-column label="比较期窗口" width="190">
                <template #default="{ row }">{{ rangeText(row.compareWindow) }}</template>
              </el-table-column>
              <el-table-column label="营业日" width="100">
                <template #default="{ row }">{{ row.baseBusinessDays }} / {{ row.compareBusinessDays }}</template>
              </el-table-column>
              <el-table-column label="闭店天数" width="100">
                <template #default="{ row }">{{ row.baseClosedDays }} / {{ row.compareClosedDays }}</template>
              </el-table-column>
              <el-table-column label="基准期净销售" width="120">
                <template #default="{ row }">{{ money(row.baseSales) }}</template>
              </el-table-column>
              <el-table-column label="比较期净销售" width="120">
                <template #default="{ row }">{{ money(row.compareSales) }}</template>
              </el-table-column>
              <el-table-column label="变化率" width="110">
                <template #default="{ row }">{{ percent(row.changeRatio) }}</template>
              </el-table-column>
              <el-table-column label="覆盖缺口" width="90">
                <template #default="{ row }">{{ row.gapCount }}</template>
              </el-table-column>
              <el-table-column label="操作" width="100">
                <template #default="{ row }">
                  <el-button size="small" @click="openDetail(row)">详情</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </template>

    <el-dialog v-model="detailVisible" :title="`${selected?.storeId} · ${alignLabel(selected?.alignment)}`" width="860px">
      <template v-if="selected">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(selected.status)" size="small">{{ selected.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="变化率">{{ percent(selected.changeRatio) }}</el-descriptions-item>
          <el-descriptions-item label="基准期节日">{{ rangeText(selected.baseHoliday) }}</el-descriptions-item>
          <el-descriptions-item label="比较期节日">{{ rangeText(selected.compareHoliday) }}</el-descriptions-item>
          <el-descriptions-item label="基准期窗口">{{ rangeText(selected.baseWindow) }}</el-descriptions-item>
          <el-descriptions-item label="比较期窗口">{{ rangeText(selected.compareWindow) }}</el-descriptions-item>
          <el-descriptions-item label="说明" :span="2">{{ selected.reason }}</el-descriptions-item>
        </el-descriptions>

        <el-divider>窗口重建</el-divider>
        <el-empty v-if="!selected.rebuilds || !selected.rebuilds.length"
                  description="窗口未重建（两期营业日数一致）" :image-size="60" />
        <el-table v-else :data="selected.rebuilds" size="small" stripe>
          <el-table-column prop="window" label="窗口" width="90" />
          <el-table-column prop="kind" label="动作" width="150" />
          <el-table-column prop="businessDays" label="补足营业日" width="110" />
          <el-table-column prop="reason" label="原因" />
        </el-table>

        <el-divider>来源覆盖缺口（未覆盖时段不按零填充）</el-divider>
        <el-empty v-if="!selected.gaps || !selected.gaps.length"
                  description="无可比窗口内的覆盖缺口" :image-size="60" />
        <el-table v-else :data="selected.gaps" size="small" stripe>
          <el-table-column prop="date" label="日期" width="110" />
          <el-table-column prop="source" label="来源" width="110" />
          <el-table-column prop="gapType" label="缺口类型" width="130" />
          <el-table-column label="覆盖小时" width="110">
            <template #default="{ row }">
              {{ row.coveredHours ?? '-' }} / {{ row.expectedHours ?? '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="message" label="说明" />
        </el-table>

        <el-divider>窗口标记</el-divider>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="节日跨财务周">
            {{ selected.flags?.holidaySplitAcrossWeeks ? '是' : '否' }}
          </el-descriptions-item>
          <el-descriptions-item label="基准期节日财务周">
            {{ (selected.flags?.baseHolidayFiscalWeeks || []).join('、') }}
          </el-descriptions-item>
          <el-descriptions-item label="比较期节日财务周">
            {{ (selected.flags?.compareHolidayFiscalWeeks || []).join('、') }}
          </el-descriptions-item>
          <el-descriptions-item label="星期营业制度变化">
            {{ selected.flags?.scheduleRegimeChanged ? '是' : '否' }}
          </el-descriptions-item>
          <el-descriptions-item label="窗口触及分析边界">
            {{ selected.flags?.phaseWindowTruncatedByBarrier ? '是' : '否' }}
          </el-descriptions-item>
          <el-descriptions-item label="地区日期策略">
            {{ selected.flags?.regionalDatePolicy }}
          </el-descriptions-item>
        </el-descriptions>
        <el-divider>说明</el-divider>
        <ul class="notes-list">
          <li v-for="(n, i) in selected.notes || []" :key="i">{{ n }}</li>
          <li v-if="!selected.notes || !selected.notes.length">无额外说明</li>
        </ul>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api/client'

const calendars = ref({ holidayCalendars: [], storeCalendars: [], eligibility: [] })
const runs = ref([])
const detail = ref(null)
const activeAlign = ref('fiscal_week')
const creating = ref(false)
const detailVisible = ref(false)
const selected = ref(null)

const form = ref({
  holidayKey: 'CNY',
  baseYear: 2025,
  compareYear: 2026,
  metricKey: 'net_sales',
  calendarVersionId: null,
  storeCalendarVersionId: null,
  eligibilityVersionId: null,
  alignments: ['fiscal_week', 'holiday_phase'],
  preBusinessDays: 14,
  postBusinessDays: 14,
  beforeWeeks: 1,
  afterWeeks: 1
})

const comparison = computed(() => detail.value?.summary?.alignmentComparison || null)

async function loadCalendars() {
  try {
    const res = await api.get('/holiday-windows/calendars')
    calendars.value = res.data
    if (!form.value.calendarVersionId && res.data.holidayCalendars.length) {
      form.value.calendarVersionId = res.data.holidayCalendars[0].id
    }
    if (!form.value.storeCalendarVersionId && res.data.storeCalendars.length) {
      form.value.storeCalendarVersionId = res.data.storeCalendars[0].id
    }
    if (!form.value.eligibilityVersionId && res.data.eligibility.length) {
      form.value.eligibilityVersionId = res.data.eligibility[0].id
    }
  } catch (e) {
    ElMessage.error('加载日历失败: ' + (e.response?.data?.error || e.message))
  }
}

async function loadRuns() {
  try {
    const res = await api.get('/holiday-windows/runs')
    runs.value = res.data.runs
  } catch (e) {
    ElMessage.error('加载运行失败: ' + (e.response?.data?.error || e.message))
  }
}

async function createRun() {
  if (!form.value.alignments.length) {
    ElMessage.warning('请至少选择一条对齐路线')
    return
  }
  creating.value = true
  try {
    const res = await api.post('/holiday-windows/runs', form.value)
    detail.value = res.data
    activeAlign.value = res.data.alignments[0]
    ElMessage.success('窗口比较运行已生成')
    await loadRuns()
  } catch (e) {
    ElMessage.error('生成失败: ' + (e.response?.data?.error || e.message))
  } finally {
    creating.value = false
  }
}

async function openRun(row) {
  try {
    const res = await api.get(`/holiday-windows/runs/${row.id}`)
    detail.value = res.data
    activeAlign.value = res.data.alignments[0]
  } catch (e) {
    ElMessage.error('加载运行详情失败: ' + (e.response?.data?.error || e.message))
  }
}

async function releaseRun() {
  try {
    const res = await api.post(`/holiday-windows/runs/${detail.value.id}/release`, { note: '经营会引用' })
    detail.value = res.data
    ElMessage.success('已发布')
    await loadRuns()
  } catch (e) {
    ElMessage.error('发布失败: ' + (e.response?.data?.error || e.message))
  }
}

function summaryFor(alignment) {
  return detail.value?.summary?.byAlignment?.[alignment] || {
    comparableStores: [], excludedStores: [], comparableCount: 0, excludedCount: 0,
    baseSalesTotal: null, compareSalesTotal: null, changeAbs: null, changeRatio: null,
    coverageGapsInComparableWindows: 0, note: ''
  }
}

function resultsFor(alignment) {
  return (detail.value?.storeResults || []).filter(r => r.alignment === alignment)
}

function openDetail(row) {
  selected.value = row
  detailVisible.value = true
}

function alignLabel(al) {
  return al === 'fiscal_week' ? '财务周路线' : '节日阶段路线'
}

function statusType(status) {
  if (status === 'comparable') return 'success'
  if (status === 'comparable_with_caveat') return 'warning'
  return 'danger'
}

function rangeText(r) {
  if (!r || !r.start) return '-'
  return `${r.start} ~ ${r.end}`
}

function money(v) {
  if (v === null || v === undefined) return '-'
  return Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

function percent(v) {
  if (v === null || v === undefined) return '-'
  return (Number(v) * 100).toFixed(2) + '%'
}

function formatTime(t) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

onMounted(() => {
  loadCalendars()
  loadRuns()
})
</script>

<style scoped>
.holiday-view { padding: 20px; }
.card { margin-bottom: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.run-form { margin-top: 8px; }
.disclaimer { margin-bottom: 16px; }
.align-card { height: 100%; }
.excluded-table { margin-top: 8px; }
.cmp-alert { margin-top: 16px; }
.stale-alert { margin-bottom: 12px; }
.hint { color: #909399; font-size: 12px; }
.note { margin-top: 8px; }
.mono { font-family: monospace; }
.ml4 { margin-left: 4px; }
.notes-list { padding-left: 18px; font-size: 13px; color: #606266; }
</style>
