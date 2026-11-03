<script setup>
import { computed } from 'vue';
import { formatNumber, itemIconUrl } from '../api';

const props = defineProps({
  /** 列表里的原始条目（装备 DTO 或背包物品 DTO） */
  item: { type: Object, required: true },
  /** WZ 详情，可能为 null */
  detail: { type: Object, default: null },
  loading: { type: Boolean, default: false },
});
const emit = defineEmits(['close', 'drop', 'gift']);

const isEquip = computed(() => Math.floor(Number(props.item.itemId) / 1000000) === 1);

/** 背包栏位：装备 1、消耗 2、设置 3、其它 4；已穿戴为负数或 -1 */
const inventoryType = computed(() => Number(props.item.inventoryType ?? (isEquip.value ? 1 : 0)));
/** 只有放在背包里的物品才支持丢弃/赠送 */
const canOperate = computed(
  () => !props.item.equipped && Number(props.item.position) > 0 && inventoryType.value >= 1 && inventoryType.value <= 4
);

const STAT_ROWS = [
  { key: 'str', label: '力量' },
  { key: 'dex', label: '敏捷' },
  { key: 'intValue', label: '智力' },
  { key: 'luk', label: '幸运' },
  { key: 'hp', label: 'HP' },
  { key: 'mp', label: 'MP' },
  { key: 'watk', label: '攻击力' },
  { key: 'matk', label: '魔力' },
  { key: 'wdef', label: '防御力' },
  { key: 'mdef', label: '魔法防御' },
  { key: 'acc', label: '命中率' },
  { key: 'avoid', label: '回避率' },
  { key: 'speed', label: '移动速度' },
  { key: 'jump', label: '跳跃力' },
];

const title = computed(() => props.detail?.name || props.item.itemName || `物品 ${props.item.itemId}`);

const statRows = computed(() => {
  const base = props.detail?.base;
  return STAT_ROWS.map((row) => {
    const now = Number(props.item[row.key] || 0);
    const raw = base ? Number(base[row.key] || 0) : null;
    const bonus = raw === null ? 0 : now - raw;
    return { ...row, now, bonus };
  }).filter((row) => row.now !== 0 || row.bonus !== 0);
});

const reqRows = computed(() => {
  const d = props.detail;
  if (!d) return [];
  const rows = [];
  if (d.reqLevel > 0) rows.push({ label: '需求等级', value: `Lv.${d.reqLevel}` });
  if (d.reqJob > 0) rows.push({ label: '需求职业', value: jobNames(d.reqJob) });
  if (d.reqStr > 0) rows.push({ label: '需求力量', value: d.reqStr });
  if (d.reqDex > 0) rows.push({ label: '需求敏捷', value: d.reqDex });
  if (d.reqInt > 0) rows.push({ label: '需求智力', value: d.reqInt });
  if (d.reqLuk > 0) rows.push({ label: '需求幸运', value: d.reqLuk });
  return rows;
});

const slotRows = computed(() => {
  const d = props.detail;
  if (!d || !d.totalSlots) return [];
  const left = Number(props.item.upgradeSlots ?? 0);
  const total = Number(d.totalSlots);
  return [
    { label: '可升级次数', value: `${left} / ${total}` },
    { label: '已升级', value: `${Math.max(0, total - left)} 次` },
  ];
});

const extraRows = computed(() => {
  const d = props.detail;
  if (!d) return [];
  const rows = [];
  if (d.price > 0) rows.push({ label: '价格', value: `${formatNumber(d.price)} 金币` });
  if (d.attackSpeed >= 2) rows.push({ label: '攻击速度', value: ATTACK_SPEED[d.attackSpeed] || `等级 ${d.attackSpeed}` });
  return rows;
});

const ATTACK_SPEED = {
  1: '极快',
  2: '更快',
  3: '快',
  4: '普通',
  5: '慢',
  6: '更慢',
  7: '极慢',
  8: '极慢',
  9: '极慢',
};

const expiry = computed(() => {
  // 服务端：-1/0 表示无期限，仅正数且落在合理年份区间才视为明确有效期
  const exp = Number(props.item.expiration ?? 0);
  if (!(exp > 0)) return '';
  const date = new Date(exp < 1e12 ? exp * 1000 : exp);
  if (Number.isNaN(date.getTime()) || date.getFullYear() < 2000) return '';
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
});

function jobNames(bits) {
  const names = [];
  if (bits & 1) names.push('战士');
  if (bits & 2) names.push('法师');
  if (bits & 4) names.push('弓箭手');
  if (bits & 8) names.push('飞侠');
  if (bits & 16) names.push('海盗');
  return names.length ? names.join('/') : '全职业';
}
</script>

<template>
  <transition name="fade">
    <div class="item-mask" @click.self="emit('close')">
      <div class="ttip">
        <header class="ttip-head">
          <img
            :src="itemIconUrl(item.itemId)"
            class="ttip-icon pixel-img"
            alt=""
            @error="(ev) => (ev.target.style.visibility = 'hidden')"
          />
          <div class="ttip-title">
            <strong>{{ title }}</strong>
            <span v-if="item.equipped" class="badge">已装备</span>
            <span v-else-if="isEquip" class="badge dim">装备栏</span>
          </div>
          <button class="ttip-close" @click="emit('close')">✕</button>
        </header>

        <div class="ttip-body">
          <div class="row">
            <span class="k">类型</span>
            <span class="v">{{ detail?.category || (isEquip ? '装备' : '道具') }}</span>
          </div>
          <div v-if="item.level > 0" class="row">
            <span class="k">强化</span>
            <span class="v up">+{{ item.level }}</span>
          </div>

          <div v-if="reqRows.length" class="req-box">
            <div v-for="row in reqRows" :key="row.label" class="row">
              <span class="k">{{ row.label }}</span>
              <span class="v req">{{ row.value }}</span>
            </div>
          </div>

          <div v-if="isEquip">
            <div v-for="row in statRows" :key="row.key" class="row">
              <span class="k">{{ row.label }}</span>
              <span class="v" :class="{ up: row.bonus > 0 }">
                +{{ row.now }}
                <em v-if="row.bonus > 0">（基础 {{ row.now - row.bonus }}）</em>
              </span>
            </div>
            <div v-if="statRows.length === 0" class="row">
              <span class="k">附加属性</span>
              <span class="v dim">无</span>
            </div>
          </div>

          <div v-if="slotRows.length" class="sep"></div>
          <div v-for="row in slotRows" :key="row.label" class="row">
            <span class="k">{{ row.label }}</span>
            <span class="v">{{ row.value }}</span>
          </div>

          <div v-if="extraRows.length" class="sep"></div>
          <div v-for="row in extraRows" :key="row.label" class="row">
            <span class="k">{{ row.label }}</span>
            <span class="v">{{ row.value }}</span>
          </div>

          <div v-if="!isEquip" class="sep"></div>
          <div v-if="!isEquip" class="row">
            <span class="k">数量</span>
            <span class="v gold">×{{ formatNumber(item.quantity) }}</span>
          </div>
          <div v-if="expiry" class="row">
            <span class="k">有效期至</span>
            <span class="v">{{ expiry }}</span>
          </div>

          <div v-if="loading" class="hint">正在读取物品资料…</div>

          <div v-if="detail?.desc" class="ttip-desc">{{ detail.desc }}</div>
        </div>

        <div v-if="canOperate" class="ttip-actions">
          <button class="act gift" @click="emit('gift')">赠送</button>
          <button class="act drop" @click="emit('drop')">丢弃</button>
        </div>
      </div>
    </div>
  </transition>
</template>

<style scoped>
.item-mask {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 18px calc(24px + var(--safe-bottom));
  background: rgba(3, 10, 16, 0.72);
  backdrop-filter: blur(3px);
}

.ttip {
  width: 100%;
  max-width: 336px;
  max-height: 74vh;
  overflow-y: auto;
  border-radius: 8px;
  color: #eaf4ff;
  font-size: 12px;
  background: linear-gradient(180deg, #172a40 0%, #0a1626 100%);
  border: 2px solid #4a6f97;
  box-shadow:
    0 0 0 1px rgba(0, 0, 0, 0.65),
    inset 0 0 0 1px rgba(120, 175, 225, 0.16),
    0 18px 50px rgba(0, 0, 0, 0.7);
}

.ttip-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 10px 9px;
  border-bottom: 1px solid rgba(96, 140, 190, 0.45);
  background: linear-gradient(180deg, rgba(40, 66, 96, 0.98), rgba(18, 34, 52, 0.98));
}

.ttip-icon {
  flex: none;
  width: 30px;
  height: 30px;
  object-fit: contain;
  filter: drop-shadow(0 2px 3px rgba(0, 0, 0, 0.5));
}

.ttip-title {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
}

.ttip-title strong {
  font-size: 14px;
  font-weight: 800;
  color: #ffffff;
  text-shadow: 0 1px 0 rgba(0, 0, 0, 0.6);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.badge {
  flex: none;
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 9px;
  font-weight: 700;
  color: #0c2318;
  background: linear-gradient(180deg, #8ef0b8, #37b772);
  border: 1px solid #b8ffd8;
}

.badge.dim {
  color: #cfe2f5;
  background: rgba(80, 120, 165, 0.4);
  border-color: rgba(120, 165, 210, 0.55);
}

.ttip-close {
  flex: none;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  font-size: 12px;
  color: #c7dcf0;
  background: rgba(255, 255, 255, 0.08);
}

.ttip-body {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 10px 12px 14px;
}

.row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 3px 0;
}

.k {
  flex: none;
  color: #92b4d6;
  font-size: 11px;
}

.v {
  text-align: right;
  font-weight: 700;
  color: #f2f9ff;
  font-variant-numeric: tabular-nums;
}

.v em {
  font-style: normal;
  font-size: 10px;
  font-weight: 400;
  color: #86a6c3;
}

.v.up {
  color: #74e6a4;
}

.v.req {
  color: #ffd479;
}

.v.gold {
  color: var(--gold);
}

.v.dim {
  font-weight: 400;
  color: #8aa7c3;
}

.req-box {
  margin: 6px 0;
  padding: 5px 9px;
  border-radius: 5px;
  background: rgba(24, 48, 74, 0.62);
  border: 1px solid rgba(80, 120, 165, 0.38);
}

.sep {
  height: 1px;
  margin: 7px 0 5px;
  background: linear-gradient(90deg, transparent, rgba(110, 155, 200, 0.55), transparent);
}

.ttip-desc {
  margin-top: 9px;
  padding-top: 8px;
  border-top: 1px dashed rgba(90, 130, 175, 0.45);
  font-size: 11px;
  line-height: 1.65;
  color: #9fbcd6;
  white-space: pre-wrap;
}

.hint {
  margin-top: 8px;
  font-size: 10px;
  color: #7e9ab5;
}

.ttip-actions {
  display: flex;
  gap: 8px;
  padding: 0 12px 12px;
}

.act {
  flex: 1;
  min-height: 34px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 700;
}

.act.gift {
  color: #0c2318;
  border: 1px solid #b8ffd8;
  background: linear-gradient(180deg, #8ef0b8, #37b772);
}

.act.drop {
  color: #ffd9d9;
  border: 1px solid rgba(220, 110, 110, 0.6);
  background: rgba(120, 34, 34, 0.45);
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.16s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
