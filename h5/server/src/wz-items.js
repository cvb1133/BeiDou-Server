/**
 * 物品详情（客户端 tooltip 数据）：需求等级/职业/属性、可升级次数、价格、描述。
 * 装备走 Character.wz 的 info 节点，普通道具走 Item.wz + String.wz。
 */
import fs from 'node:fs';
import path from 'node:path';
import * as wz from '@tybys/wz';
import { DATA_DIR } from './wz-data.js';
import { equipFileRel } from './wz-icons.js';

/** 背包分类（itemId 百万位）→ Item.wz 目录 / String.wz 文件 / 展示名 */
const ITEM_CATEGORY = {
  2: { dir: 'Consume', str: 'Consume', label: '消耗品' },
  3: { dir: 'Install', str: 'Ins', label: '设置道具' },
  4: { dir: 'Etc', str: 'Etc', label: '其它道具' },
  5: { dir: 'Cash', str: 'Cash', label: '现金道具' },
  9: { dir: 'Special', str: 'Etc', label: '特殊道具' },
};

const EQUIP_LABEL = {
  Cap: '帽子',
  Accessory: '饰品',
  Coat: '上衣',
  Longcoat: '套服',
  Pants: '裤裙',
  Shoes: '鞋子',
  Glove: '手套',
  Cape: '披风',
  Shield: '盾牌',
  Weapon: '武器',
  Ring: '戒指',
  Face: '脸型',
  Hair: '发型',
  PetEquip: '宠物装备',
  Dragon: '龙装备',
};

/** DTO 字段名 → Character.wz info 里的基础加成字段 */
const BASE_KEYS = {
  str: 'incSTR',
  dex: 'incDEX',
  intValue: 'incINT',
  luk: 'incLUK',
  hp: 'incMHP',
  mp: 'incMMP',
  watk: 'incPAD',
  matk: 'incMAD',
  wdef: 'incPDD',
  mdef: 'incMDD',
  acc: 'incACC',
  avoid: 'incEVA',
  hands: 'incMND',
  speed: 'incSpeed',
  jump: 'incJump',
};

const fileCache = new Map();
const detailCache = new Map();
const MAX_FILES = 16;

let initialized = false;

function pad(num, len) {
  return String(num).padStart(len, '0');
}

async function load(rel) {
  const hit = fileCache.get(rel);
  if (hit) {
    fileCache.delete(rel);
    fileCache.set(rel, hit);
    return hit;
  }
  const full = path.join(DATA_DIR, rel);
  if (!fs.existsSync(full)) return null;
  if (!initialized) {
    await wz.init();
    initialized = true;
  }
  const img = wz.WzImage.createFromFile(full, wz.WzMapleVersion.GMS);
  const ok = await img.parseImage(true);
  if (!ok) return null;
  fileCache.set(rel, img);
  while (fileCache.size > MAX_FILES) {
    const oldest = fileCache.keys().next().value;
    try {
      fileCache.get(oldest).dispose();
    } catch {
      /* ignore */
    }
    fileCache.delete(oldest);
  }
  return img;
}

function num(node) {
  if (!node) return 0;
  const v = node.value;
  return typeof v === 'number' ? v : 0;
}

function str(node) {
  const v = node?.value;
  return typeof v === 'string' ? v : '';
}

/** String/Eqp.img 里按分类取装备名，找不到时全分类兜底 */
function equipName(root, dir, itemId) {
  if (!root) return '';
  const direct = root.at(dir)?.at(String(itemId));
  const name = str(direct?.at('name'));
  if (name) return name;
  for (const p of Array.from(root.wzProperties || [])) {
    const hit = p.at(String(itemId));
    const found = str(hit?.at('name'));
    if (found) return found;
  }
  return '';
}

async function equipDetail(itemId) {
  const rel = equipFileRel(itemId);
  if (!rel) return null;
  const dir = rel.split('/')[1];
  const img = await load(rel);
  const info = img?.at('info');
  const eqpImg = await load('String/Eqp.img');
  const base = {};
  for (const [key, wzKey] of Object.entries(BASE_KEYS)) {
    base[key] = num(info?.at(wzKey));
  }
  return {
    itemId,
    kind: 'equip',
    category: EQUIP_LABEL[dir] || dir,
    name: equipName(eqpImg?.at('Eqp'), dir, itemId),
    desc: '',
    reqLevel: num(info?.at('reqLevel')),
    reqJob: num(info?.at('reqJob')),
    reqStr: num(info?.at('reqSTR')),
    reqDex: num(info?.at('reqDEX')),
    reqInt: num(info?.at('reqINT')),
    reqLuk: num(info?.at('reqLUK')),
    totalSlots: num(info?.at('tuc')),
    price: num(info?.at('price')),
    attackSpeed: num(info?.at('attackSpeed')),
    base,
  };
}

/** String/Etc.img 之类把道具挂在分类子节点下，这里做一次兜底查找 */
function strNodeFor(img, itemId) {
  if (!img) return null;
  const direct = img.at(String(itemId));
  if (direct) return direct;
  for (const p of Array.from(img.wzProperties || [])) {
    const hit = p.at(String(itemId));
    if (hit) return hit;
  }
  return null;
}

async function plainDetail(itemId) {
  const million = Math.floor(itemId / 1000000);
  const category = ITEM_CATEGORY[million];
  if (!category) return null;
  const fileRel = `Item/${category.dir}/${pad(Math.floor(itemId / 10000), 4)}.img`;
  const img = await load(fileRel);
  const info = img?.at(pad(itemId, 8))?.at('info');
  const strImg = await load(`String/${category.str}.img`);
  const strNode = strNodeFor(strImg, itemId);
  return {
    itemId,
    kind: 'item',
    category: category.label,
    name: str(strNode?.at('name')),
    desc: str(strNode?.at('desc')).replace(/\\n/g, '\n'),
    reqLevel: 0,
    reqJob: 0,
    totalSlots: 0,
    price: num(info?.at('price')),
    attackSpeed: 0,
    base: null,
  };
}

/** 返回物品详情；资源缺失时返回 null 由调用方降级 */
export async function getItemDetail(itemId) {
  if (!Number.isInteger(itemId) || itemId <= 0) return null;
  if (detailCache.has(itemId)) return detailCache.get(itemId);
  const detail = Math.floor(itemId / 1000000) === 1 ? await equipDetail(itemId) : await plainDetail(itemId);
  if (detail) {
    if (detailCache.size > 3000) detailCache.clear();
    detailCache.set(itemId, detail);
  }
  return detail;
}
