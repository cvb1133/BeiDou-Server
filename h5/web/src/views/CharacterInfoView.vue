<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { api, formatNumber, itemIconUrl } from '../api';
import BottomNav from '../components/BottomNav.vue';
import ItemDetail from '../components/ItemDetail.vue';
import PowerHelp from '../components/PowerHelp.vue';

const props = defineProps({
  character: { type: Object, required: true },
});
const emit = defineEmits(['back']);

const info = ref(null);
const loading = ref(true);
const error = ref('');
const tab = ref('stats');
const bagTab = ref('use');
const detailItem = ref(null);
const detailExtra = ref(null);
const detailLoading = ref(false);
let detailSeq = 0;
const powerHelpOpen = ref(false);

const avatarSrc = ref('');
const avatarState = ref('loading');
let avatarObjectUrl = '';

const equippedChips = computed(() => (info.value?.equipped || []).filter((e) => e.itemName));

async function loadAvatar() {
  avatarState.value = 'loading';
  try {
    const url = await api.avatar(props.character.characterId);
    if (avatarObjectUrl) URL.revokeObjectURL(avatarObjectUrl);
    avatarObjectUrl = url;
    avatarSrc.value = url;
    avatarState.value = 'ok';
  } catch {
    avatarState.value = 'fail';
  }
}

/** 单击装备/道具：先用本地已有属性渲染，再异步补全 WZ 需求与描述 */
async function openDetail(item) {
  detailItem.value = item;
  detailExtra.value = null;
  detailLoading.value = true;
  const seq = ++detailSeq;
  try {
    const detail = await api.itemDetail(item.itemId);
    if (seq === detailSeq) detailExtra.value = detail;
  } catch {
    if (seq === detailSeq) detailExtra.value = {};
  } finally {
    if (seq === detailSeq) detailLoading.value = false;
  }
}

function closeDetail() {
  detailSeq += 1;
  detailItem.value = null;
  detailExtra.value = null;
  detailLoading.value = false;
}

/* ---------- 丢弃 / 赠送 ---------- */
const itemAction = ref(null);
const actionBusy = ref(false);
const giftForm = ref({ receiver: '', message: '', quantity: 1 });
const toast = ref(null);
let toastTimer = null;

/** 装备栏位为 1，不可堆叠，数量固定为 1 */
const actionStackable = computed(() => {
  const item = itemAction.value?.item;
  if (!item) return false;
  return Number(item.inventoryType ?? (Math.floor(Number(item.itemId) / 1000000) === 1 ? 1 : 0)) !== 1;
});

function showToast(text, type = 'ok') {
  toast.value = { text, type };
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (toast.value = null), 2200);
}

function openAction(kind) {
  const item = detailItem.value;
  if (!item) return;
  closeDetail();
  giftForm.value = { receiver: '', message: '', quantity: Number(item.quantity) || 1 };
  itemAction.value = { kind, item };
}

function closeAction() {
  if (actionBusy.value) return;
  itemAction.value = null;
}

function actionPayload(item) {
  return {
    inventoryType: Number(item.inventoryType ?? (Math.floor(Number(item.itemId) / 1000000) === 1 ? 1 : 0)),
    position: Number(item.position),
  };
}

async function submitDrop() {
  if (actionBusy.value) return;
  const item = itemAction.value.item;
  actionBusy.value = true;
  try {
    info.value = await api.dropItem(props.character.characterId, {
      ...actionPayload(item),
      quantity: actionStackable.value ? Number(item.quantity) : 1,
    });
    itemAction.value = null;
    showToast(`已丢弃「${item.itemName}」`);
  } catch (e) {
    showToast(e.message || '丢弃失败', 'error');
  } finally {
    actionBusy.value = false;
  }
}

async function submitGift() {
  if (actionBusy.value) return;
  const item = itemAction.value.item;
  if (!giftForm.value.receiver) {
    showToast('请输入收件人角色名', 'error');
    return;
  }
  const max = Number(item.quantity) || 1;
  const quantity = actionStackable.value ? Math.min(Math.max(1, Number(giftForm.value.quantity) || 1), max) : 1;
  actionBusy.value = true;
  try {
    info.value = await api.giftItem(props.character.characterId, {
      ...actionPayload(item),
      quantity,
      receiverName: giftForm.value.receiver,
      message: giftForm.value.message,
    });
    itemAction.value = null;
    showToast(`已将「${item.itemName}」寄给 ${giftForm.value.receiver}`);
  } catch (e) {
    showToast(e.message || '赠送失败', 'error');
  } finally {
    actionBusy.value = false;
  }
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

const TABS = [
  { key: 'stats', label: '属性' },
  { key: 'skills', label: '技能' },
  { key: 'equip', label: '装备' },
  { key: 'items', label: '道具' },
];

const BAG_TABS = [
  { key: 'use', label: '消耗', list: 'useItems' },
  { key: 'setup', label: '设置', list: 'setupItems' },
  { key: 'etc', label: '其它', list: 'etcItems' },
  { key: 'cash', label: '现金', list: 'cashItems' },
];

const bagList = computed(() => {
  if (!info.value) return [];
  const hit = BAG_TABS.find((t) => t.key === bagTab.value);
  return hit ? info.value[hit.list] || [] : [];
});

const bagCounts = computed(() => {
  const counts = {};
  if (info.value) {
    BAG_TABS.forEach((t) => (counts[t.key] = (info.value[t.list] || []).length));
  }
  return counts;
});

const hpPercent = computed(() => {
  if (!info.value || !info.value.maxHp) return 0;
  return Math.min(100, Math.round((info.value.hp / info.value.maxHp) * 100));
});

const mpPercent = computed(() => {
  if (!info.value || !info.value.maxMp) return 0;
  return Math.min(100, Math.round((info.value.mp / info.value.maxMp) * 100));
});

const STAT_LINES = [
  { key: 'str', label: '力量' },
  { key: 'dex', label: '敏捷' },
  { key: 'int', label: '智力' },
  { key: 'luk', label: '幸运' },
  { key: 'watk', label: '攻击力' },
  { key: 'matk', label: '魔力' },
  { key: 'wdef', label: '物防' },
  { key: 'mdef', label: '魔防' },
  { key: 'acc', label: '命中' },
  { key: 'avoid', label: '回避' },
  { key: 'speed', label: '移速' },
  { key: 'jump', label: '跳跃' },
];

const EQUIP_LINES = [
  { key: 'str', label: '力量' },
  { key: 'dex', label: '敏捷' },
  { key: 'intValue', label: '智力' },
  { key: 'luk', label: '幸运' },
  { key: 'hp', label: 'HP' },
  { key: 'mp', label: 'MP' },
  { key: 'watk', label: '攻' },
  { key: 'matk', label: '魔攻' },
  { key: 'wdef', label: '防' },
  { key: 'mdef', label: '魔防' },
  { key: 'acc', label: '命中' },
  { key: 'avoid', label: '回避' },
  { key: 'speed', label: '移速' },
  { key: 'jump', label: '跳跃' },
];

function statOf(key) {
  const rows = info.value?.stats || [];
  return rows.find((r) => r.key === key) || { key, label: key, base: 0, bonus: 0, total: 0 };
}

function equipLineText(equip) {
  const parts = EQUIP_LINES.filter((line) => Number(equip[line.key] || 0) !== 0)
    .map((line) => `${line.label}+${equip[line.key]}`);
  if (equip.upgradeSlots > 0) parts.push(`可升级 ${equip.upgradeSlots}`);
  if (equip.level > 0) parts.push(`+${equip.level}`);
  return parts.join(' · ') || '无附加属性';
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    info.value = await api.characterInfo(props.character.characterId);
  } catch (e) {
    error.value = e.message || '角色信息读取失败';
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  load();
  loadAvatar();
});

onUnmounted(() => {
  if (avatarObjectUrl) URL.revokeObjectURL(avatarObjectUrl);
});
</script>

<template>
  <div class="info-page">
    <header class="topbar">
      <button class="back" @click="emit('back')">‹</button>
      <div class="who">
        <strong>{{ info?.characterName || character.characterName }}</strong>
        <span class="muted">
          Lv.{{ info?.level ?? character.level }} · {{ info?.jobName || '—' }}
        </span>
      </div>
    </header>

    <div v-if="loading" class="tip muted">正在读取角色数据…</div>
    <div v-else-if="error" class="tip error">
      {{ error }}
      <button class="btn small ghost" @click="load">重试</button>
    </div>

    <template v-else-if="info">
      <section class="card profile">
        <div class="avatar-stage">
          <div class="avatar-frame">
            <img v-if="avatarSrc" :src="avatarSrc" class="avatar-img" alt="角色形象" />
            <div v-else class="avatar-placeholder muted">
              {{ avatarState === 'fail' ? '形象暂不可用' : '形象生成中…' }}
            </div>
            <i class="avatar-shadow"></i>
          </div>
          <div class="avatar-info">
            <div class="avatar-head">
              <span class="avatar-tag">纸娃娃</span>
              <strong>当前装扮</strong>
            </div>
            <div class="avatar-chips">
              <span v-for="e in equippedChips" :key="e.itemId + '-' + e.position" class="avatar-chip">
                <img
                  :src="itemIconUrl(e.itemId)"
                  class="avatar-chip-icon pixel-img"
                  alt=""
                  @error="(ev) => (ev.target.style.visibility = 'hidden')"
                />
                {{ e.itemName }}
              </span>
              <span v-if="equippedChips.length === 0" class="muted">未穿戴装备，快去冒险吧</span>
            </div>
          </div>
        </div>
        <div class="bars">
          <div class="bar-row">
            <span class="bar-label">HP</span>
            <div class="bar hp"><i :style="{ width: hpPercent + '%' }"></i></div>
            <span class="bar-num">{{ info.hp }}/{{ info.maxHp }}</span>
          </div>
          <div class="bar-row">
            <span class="bar-label">MP</span>
            <div class="bar mp"><i :style="{ width: mpPercent + '%' }"></i></div>
            <span class="bar-num">{{ info.mp }}/{{ info.maxMp }}</span>
          </div>
        </div>
        <div class="power-strip">
          <div class="power-main">
            <span class="muted">角色战斗力</span>
            <strong>{{ formatNumber(info.dps) }}</strong>
            <span class="power-tag">估算 DPS</span>
          </div>
          <button class="power-help" @click="powerHelpOpen = true">
            计算说明
            <span>›</span>
          </button>
        </div>
        <div class="profile-grid">
          <div><span class="muted">经验</span><strong>{{ formatNumber(info.exp) }}</strong></div>
          <div><span class="muted">金币</span><strong class="gold-text">{{ formatNumber(info.meso) }}</strong></div>
          <div><span class="muted">剩余能力点</span><strong>{{ info.ap }}</strong></div>
          <div><span class="muted">剩余技能点</span><strong>{{ info.sp }}</strong></div>
          <div><span class="muted">人气</span><strong>{{ info.fame }}</strong></div>
          <div><span class="muted">攻击类型</span><strong>{{ info.attackType === 'magic' ? '魔法' : '物理' }}</strong></div>
        </div>
      </section>

      <nav class="tabs">
        <button v-for="t in TABS" :key="t.key" class="tab" :class="{ active: tab === t.key }" @click="tab = t.key">
          {{ t.label }}
        </button>
      </nav>

      <!-- 属性 -->
      <section v-if="tab === 'stats'" class="card panel">
        <div class="stat-grid">
          <div v-for="line in STAT_LINES" :key="line.key" class="stat-cell">
            <span class="muted">{{ line.label }}</span>
            <strong>{{ statOf(line.key).total }}</strong>
            <span class="sub muted">
              基础 {{ statOf(line.key).base }}
              <template v-if="statOf(line.key).bonus">+ 装备 {{ statOf(line.key).bonus }}</template>
            </span>
          </div>
        </div>
        <div class="divider"></div>
        <div class="hint muted">
          属性为服务端数据库中的当前值，装备加成按已穿戴装备汇总。
        </div>
      </section>

      <!-- 技能 -->
      <section v-else-if="tab === 'skills'" class="card panel">
        <div class="section-title">
          已学技能
          <span class="muted">{{ info.skills.length }} 个</span>
        </div>
        <div class="skill-list">
          <div v-for="s in info.skills" :key="s.skillId" class="skill">
            <div class="skill-main">
              <span class="skill-name">{{ s.name }}</span>
              <span class="muted">技能 ID {{ s.skillId }}</span>
            </div>
            <span class="chip">Lv.{{ s.level }}<template v-if="s.masterLevel"> / {{ s.masterLevel }}</template></span>
          </div>
          <div v-if="info.skills.length === 0" class="tip muted">暂无已学技能</div>
        </div>
      </section>

      <!-- 装备 -->
      <section v-else-if="tab === 'equip'" class="card panel">
        <div class="section-title">
          已装备
          <span class="muted">{{ info.equipped.length }} 件</span>
        </div>
        <div class="equip-grid">
          <div
            v-for="e in info.equipped"
            :key="e.itemId + '-' + e.position"
            class="equip-cell on"
            @click="openDetail(e)"
          >
            <img :src="itemIconUrl(e.itemId)" class="icon pixel-img" alt="" @error="(ev) => (ev.target.style.visibility = 'hidden')" />
            <span class="equip-name">{{ e.itemName }}</span>
            <span class="equip-sub muted">{{ equipLineText(e) }}</span>
          </div>
          <div v-if="info.equipped.length === 0" class="tip muted">未穿戴装备</div>
        </div>

        <div class="section-title">
          装备栏
          <span class="muted">{{ info.equips.length }} 件</span>
        </div>
        <div class="equip-grid">
          <div
            v-for="e in info.equips"
            :key="e.itemId + '-' + e.position"
            class="equip-cell"
            @click="openDetail(e)"
          >
            <img :src="itemIconUrl(e.itemId)" class="icon pixel-img" alt="" @error="(ev) => (ev.target.style.visibility = 'hidden')" />
            <span class="equip-name">{{ e.itemName }}</span>
            <span class="equip-sub muted">{{ equipLineText(e) }}</span>
          </div>
          <div v-if="info.equips.length === 0" class="tip muted">装备栏为空</div>
        </div>
      </section>

      <!-- 道具 -->
      <section v-else class="card panel">
        <div class="bag-tabs">
          <button
            v-for="b in BAG_TABS"
            :key="b.key"
            class="bag-tab"
            :class="{ active: bagTab === b.key }"
            @click="bagTab = b.key"
          >
            {{ b.label }}<span class="count">{{ bagCounts[b.key] }}</span>
          </button>
        </div>
        <div class="item-list">
          <div v-for="it in bagList" :key="it.itemId + '-' + it.position" class="item" @click="openDetail(it)">
            <img :src="itemIconUrl(it.itemId)" class="icon pixel-img" alt="" @error="(ev) => (ev.target.style.visibility = 'hidden')" />
            <span class="item-name">{{ it.itemName }}</span>
            <span class="item-qty gold-text">×{{ it.quantity }}</span>
          </div>
          <div v-if="bagList.length === 0" class="tip muted">该栏暂无物品</div>
        </div>
      </section>
    </template>

    <ItemDetail
      v-if="detailItem"
      :item="detailItem"
      :detail="detailExtra"
      :loading="detailLoading"
      @close="closeDetail"
      @drop="openAction('drop')"
      @gift="openAction('gift')"
    />

    <div v-if="itemAction" class="action-mask" @click.self="closeAction">
      <div class="action-card">
        <div class="action-head">
          <img :src="itemIconUrl(itemAction.item.itemId)" class="icon pixel-img" alt="" />
          <div class="action-title">
            <strong>{{ itemAction.item.itemName }}</strong>
            <span class="muted">
              {{ itemAction.kind === 'drop' ? '丢弃后无法找回' : '通过快递寄给对方' }}
            </span>
          </div>
          <button class="action-x" @click="closeAction">✕</button>
        </div>

        <template v-if="itemAction.kind === 'gift'">
          <label class="field">
            <span>收件人角色名</span>
            <input v-model.trim="giftForm.receiver" maxlength="13" placeholder="请输入对方角色名" />
          </label>
          <label v-if="actionStackable" class="field">
            <span>赠送数量</span>
            <input v-model.number="giftForm.quantity" type="number" min="1" :max="Number(itemAction.item.quantity)" />
          </label>
          <label class="field">
            <span>留言（可选）</span>
            <input v-model.trim="giftForm.message" maxlength="100" placeholder="最多 100 字" />
          </label>
          <div class="action-tip muted">赠送的物品会以快递包裹送达，对方需在游戏内找快递员领取。</div>
          <div class="action-btns">
            <button class="btn ghost" :disabled="actionBusy" @click="closeAction">取消</button>
            <button class="btn gold" :disabled="actionBusy" @click="submitGift">
              {{ actionBusy ? '提交中…' : '确认赠送' }}
            </button>
          </div>
        </template>

        <template v-else>
          <div class="action-tip">
            确定丢弃「{{ itemAction.item.itemName }}」<template v-if="actionStackable"> ×{{ itemAction.item.quantity }}</template>吗？
            物品将直接从背包移除，无法找回。
          </div>
          <div class="action-btns">
            <button class="btn ghost" :disabled="actionBusy" @click="closeAction">取消</button>
            <button class="btn danger" :disabled="actionBusy" @click="submitDrop">
              {{ actionBusy ? '提交中…' : '确认丢弃' }}
            </button>
          </div>
        </template>
      </div>
    </div>

    <div v-if="toast" class="toast" :class="toast.type">{{ toast.text }}</div>

    <BottomNav active="info" @hangup="emit('back')" @info="scrollToTop" />
    <PowerHelp :show="powerHelpOpen" @close="powerHelpOpen = false" />
  </div>
</template>

<style scoped>
.info-page {
  min-height: 100vh;
  padding: 0 14px calc(84px + var(--safe-bottom));
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 0;
  background: linear-gradient(180deg, rgba(7, 24, 34, 0.95) 70%, rgba(7, 24, 34, 0));
  backdrop-filter: blur(6px);
}

.back {
  width: 36px;
  height: 36px;
  font-size: 24px;
  line-height: 1;
  color: var(--gold);
  border: 1px solid var(--line);
  border-radius: 10px;
  background: rgba(255, 209, 92, 0.08);
}

.who {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.who strong {
  font-size: 16px;
}

.who .muted {
  font-size: 11px;
}

.power-strip {
  position: relative;
  overflow: hidden;
  margin-top: 12px;
  padding: 10px 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  border-radius: 10px;
  border: 1px solid rgba(255, 209, 92, 0.42);
  background: linear-gradient(135deg, rgba(255, 190, 70, 0.16), rgba(12, 34, 46, 0.9) 70%);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.12);
}

.power-strip::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: radial-gradient(240px 70px at 92% 0%, rgba(255, 190, 70, 0.22), transparent 72%);
}

.power-main {
  position: relative;
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px;
}

.power-main .muted {
  width: 100%;
  font-size: 10px;
  letter-spacing: 0.5px;
}

.power-main strong {
  font-size: 26px;
  font-weight: 900;
  line-height: 1.1;
  color: var(--gold);
  font-variant-numeric: tabular-nums;
  text-shadow: 0 0 12px rgba(255, 190, 70, 0.4), 0 2px 0 rgba(0, 0, 0, 0.35);
}

.power-tag {
  font-size: 9px;
  padding: 2px 6px;
  border-radius: 4px;
  color: var(--gold);
  border: 1px solid rgba(255, 209, 92, 0.5);
  background: rgba(255, 209, 92, 0.12);
}

.power-help {
  position: relative;
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  min-height: 34px;
  padding: 0 10px;
  border-radius: 8px;
  font-size: 11px;
  font-weight: 700;
  color: var(--gold);
  border: 1px solid rgba(255, 209, 92, 0.55);
  background: rgba(255, 209, 92, 0.1);
}

.power-help span {
  font-size: 13px;
}

.profile {
  padding: 14px;
}

.avatar-stage {
  display: flex;
  gap: 12px;
  margin-bottom: 14px;
}

.avatar-frame {
  position: relative;
  flex: none;
  width: 94px;
  min-height: 128px;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  padding-bottom: 12px;
  border-radius: 10px;
  border: 1px solid var(--line-soft);
  background:
    radial-gradient(78px 66px at 50% 76%, rgba(255, 209, 92, 0.16), transparent 72%),
    linear-gradient(180deg, rgba(20, 52, 68, 0.92), rgba(8, 22, 32, 0.92));
  overflow: hidden;
}

.avatar-img {
  position: relative;
  z-index: 1;
  height: 108px;
  width: auto;
  image-rendering: pixelated;
  filter: drop-shadow(0 5px 7px rgba(0, 0, 0, 0.45));
}

.avatar-shadow {
  position: absolute;
  left: 50%;
  bottom: 10px;
  width: 56px;
  height: 12px;
  transform: translateX(-50%);
  border-radius: 50%;
  background: radial-gradient(closest-side, rgba(0, 0, 0, 0.6), transparent);
}

.avatar-placeholder {
  padding: 0 8px 22px;
  font-size: 11px;
  line-height: 1.5;
  text-align: center;
}

.avatar-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.avatar-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.avatar-tag {
  padding: 2px 7px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 700;
  color: var(--gold);
  border: 1px solid rgba(255, 209, 92, 0.55);
  background: rgba(255, 209, 92, 0.12);
}

.avatar-chips {
  display: flex;
  flex-wrap: wrap;
  align-content: flex-start;
  gap: 6px;
  max-height: 100px;
  overflow-y: auto;
  scrollbar-width: none;
}

.avatar-chips::-webkit-scrollbar {
  display: none;
}

.avatar-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  padding: 3px 7px 3px 3px;
  border-radius: 6px;
  font-size: 10px;
  line-height: 1.3;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--text);
  border: 1px solid var(--line-soft);
  background: rgba(10, 30, 42, 0.65);
}

.avatar-chip-icon {
  flex: none;
  width: 18px;
  height: 18px;
  object-fit: contain;
}

.bars {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.bar-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.bar-label {
  width: 22px;
  font-size: 11px;
  color: var(--text-dim);
}

.bar {
  flex: 1;
  height: 8px;
  border-radius: 4px;
  background: rgba(8, 14, 28, 0.8);
  border: 1px solid var(--line-soft);
  overflow: hidden;
}

.bar i {
  display: block;
  height: 100%;
  border-radius: 3px;
  transition: width 0.25s ease;
}

.bar.hp i {
  background: linear-gradient(90deg, #ff8a8a, #e04545);
}

.bar.mp i {
  background: linear-gradient(90deg, #8fd4ff, #3b8fe0);
}

.bar-num {
  min-width: 78px;
  text-align: right;
  font-size: 11px;
  color: var(--text-dim);
}

.profile-grid {
  margin-top: 12px;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px 8px;
}

.profile-grid > div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.profile-grid .muted {
  font-size: 10px;
}

.profile-grid strong {
  font-size: 14px;
}

.gold-text {
  color: var(--gold);
}

.tabs {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding: 14px 0 10px;
  scrollbar-width: none;
}

.tabs::-webkit-scrollbar {
  display: none;
}

.tab {
  flex: 1;
  min-height: 40px;
  padding: 9px 12px;
  border-radius: 8px;
  font-size: 13px;
  color: var(--text-dim);
  border: 1px solid var(--line);
  background: rgba(20, 44, 60, 0.8);
}

.tab.active {
  color: #3d2400;
  font-weight: 800;
  border-color: #ffe9a8;
  background: linear-gradient(180deg, #ffe08a 0%, #ffc14d 50%, #ee921d 100%);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.7), 0 2px 0 #8d5109;
}

.panel {
  padding: 14px;
}

.section-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin: 4px 0 10px;
  font-size: 13px;
  font-weight: 700;
}

.section-title .muted {
  font-size: 10px;
  font-weight: 400;
}

.section-title + .equip-grid {
  margin-bottom: 18px;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}

.stat-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border-radius: 6px;
  background: rgba(10, 30, 42, 0.55);
  border: 1px solid var(--line-soft);
}

.stat-cell .muted {
  font-size: 10px;
}

.stat-cell strong {
  font-size: 18px;
}

.stat-cell .sub {
  font-size: 10px;
}

.hint {
  font-size: 11px;
  line-height: 1.6;
}

.skill-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.skill {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  background: rgba(10, 30, 42, 0.55);
  border: 1px solid var(--line-soft);
}

.skill-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.skill-name {
  font-size: 13px;
  font-weight: 700;
}

.skill-main .muted {
  font-size: 10px;
}

.equip-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}

.equip-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 10px;
  border-radius: 6px;
  background: rgba(10, 30, 42, 0.55);
  border: 1px solid var(--line-soft);
  cursor: pointer;
}

.equip-cell.on {
  border-color: rgba(94, 227, 154, 0.5);
  background: rgba(47, 143, 94, 0.12);
}

.icon {
  width: 26px;
  height: 26px;
  object-fit: contain;
}

.icon.big {
  width: 40px;
  height: 40px;
}

.equip-name {
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.equip-sub {
  font-size: 10px;
  line-height: 1.4;
}

.bag-tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.bag-tab {
  flex: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 12px;
  color: var(--text-dim);
  border: 1px solid var(--line-soft);
  background: rgba(10, 30, 42, 0.5);
}

.bag-tab.active {
  color: var(--gold);
  border-color: rgba(255, 209, 92, 0.6);
  background: rgba(255, 209, 92, 0.14);
}

.bag-tab .count {
  font-size: 10px;
  opacity: 0.8;
}

.item-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 10px;
  border-radius: 6px;
  background: rgba(10, 30, 42, 0.5);
  border: 1px solid var(--line-soft);
  cursor: pointer;
}

.item:active,
.equip-cell:active {
  transform: scale(0.99);
}

.item-name {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.item-qty {
  font-size: 12px;
}

.tip {
  padding: 20px 0;
  text-align: center;
  color: var(--text-dim);
  font-size: 12px;
}

.tip.error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  color: #ffb3b3;
}

/* ---------- 丢弃 / 赠送弹层 ---------- */
.action-mask {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 18px;
  background: rgba(2, 8, 14, 0.72);
  backdrop-filter: blur(2px);
}

.action-card {
  width: 100%;
  max-width: 360px;
  max-height: 82vh;
  overflow-y: auto;
  padding: 14px;
  border-radius: 12px;
  border: 1px solid rgba(214, 166, 91, 0.55);
  background: linear-gradient(180deg, #16334a 0%, #0c2233 100%);
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.55);
}

.action-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 10px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.action-head .icon {
  width: 34px;
  height: 34px;
  object-fit: contain;
  padding: 3px;
  border-radius: 6px;
  border: 1px solid rgba(214, 166, 91, 0.45);
  background: radial-gradient(circle at 50% 30%, #29506b, #0c1b27 70%);
}

.action-title {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.action-title strong {
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.action-title .muted {
  font-size: 11px;
}

.action-x {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  font-size: 14px;
  color: var(--text-dim);
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.04);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-top: 12px;
  font-size: 12px;
  color: var(--text-dim);
}

.field input {
  min-height: 38px;
  padding: 0 10px;
  border-radius: 8px;
  font-size: 13px;
  border: 1px solid rgba(214, 166, 91, 0.4);
  background: rgba(3, 12, 20, 0.65);
}

.action-tip {
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-dim);
}

.action-btns {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}

.action-btns .btn {
  flex: 1;
}

.btn.danger {
  border-color: #ffb3b3;
  background: linear-gradient(180deg, #f08a8a 0%, #d04444 55%, #a52323 100%);
  color: #2a0505;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.45), 0 3px 0 #651010, 0 6px 14px rgba(0, 0, 0, 0.38);
}

.toast {
  position: fixed;
  left: 50%;
  bottom: 92px;
  z-index: 70;
  transform: translateX(-50%);
  max-width: 82vw;
  padding: 9px 16px;
  border-radius: 999px;
  font-size: 12px;
  text-align: center;
  color: #d8ffe4;
  border: 1px solid rgba(120, 230, 160, 0.45);
  background: rgba(12, 46, 30, 0.92);
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.45);
}

.toast.error {
  color: #ffd9d9;
  border-color: rgba(230, 120, 120, 0.5);
  background: rgba(56, 16, 16, 0.92);
}
</style>
