// 商品图片工具：picsum 稳定占位 + 空值兜底。
// picsum seed 模式（https://picsum.photos/seed/{seed}/{w}/{h}）保证同 seed 永远返回同一张图，
// 因此商品未配置图片时用「item-{id|name}」作 seed，同一商品始终显示同一张占位图。

const PICSUM_BASE = 'https://picsum.photos'

/**
 * 生成 picsum 稳定图 URL。
 * @param {string|number} seed 图片种子，同 seed 同图
 * @param {number} w 宽（默认 400）
 * @param {number} h 高（默认与宽相同）
 */
export function picsumUrl(seed = 'item', w = 400, h = w) {
  return `${PICSUM_BASE}/seed/${encodeURIComponent(String(seed))}/${w}/${h}`
}

/**
 * 商品展示图：image 有值用原值（绝对 http(s) URL 或本服务 /images/xxx 相对路径均可），
 * 为空则返回 picsum 稳定占位图。
 */
export function itemImageUrl(image, seed = 'item') {
  const v = (image || '').trim()
  return v ? v : picsumUrl(seed)
}

/**
 * 由商品 id/名称生成稳定 seed：优先 id（编辑态），无 id 用名称（新增态）。
 */
export function itemSeed(id, name = '') {
  if (id !== null && id !== undefined && id !== '') return `item-${id}`
  const n = (name || '').trim()
  return `item-${n || 'new'}`
}

/**
 * img onerror 兜底处理：原图加载失败（/images 死链、外链不可达）时切到 picsum 占位；
 * picsum 也失败（离线）则隐藏图片，避免裂图图标。
 * @param {Event} e 原生 img 的 error 事件
 * @param {string} [seed] 占位图种子，默认读取 img[data-seed]
 */
export function fallbackToPicsum(e, seed) {
  const img = e.currentTarget
  if (!img || !img.dataset) return
  if (img.dataset.fbk) {
    img.style.visibility = 'hidden'
    return
  }
  img.dataset.fbk = '1'
  img.src = picsumUrl(seed || img.dataset.seed || 'item', 480)
}
