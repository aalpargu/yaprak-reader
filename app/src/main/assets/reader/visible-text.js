(function () {
  // v1: conservative, complete visible text nodes only. No inferred chapter coverage.
  // Paths index childNodes from body. UTF-16 offsets and text hashes must be verified
  // against this extraction version before a future recap is allowed to use the text.
  if (!document.body || document.visibilityState !== 'visible') return null;
  const width = window.innerWidth, height = window.innerHeight;
  if (width <= 0 || height <= 0) return null;
  const result = [];
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  let node, visited = 0;
  while ((node = walker.nextNode()) && ++visited <= 20000 && result.length < 256) {
    const text = node.nodeValue;
    if (!text || !text.trim() || text.length > 4096) continue;
    let element = node.parentElement, safe = true;
    while (element) {
      const style = getComputedStyle(element);
      if (/^(SCRIPT|STYLE|NOSCRIPT|SVG|MATH|RT)$/.test(element.tagName) ||
          style.display === 'none' || style.visibility !== 'visible' ||
          Number(style.opacity) !== 1 || style.clipPath !== 'none' ||
          style.transform !== 'none' || style.textOverflow === 'ellipsis') { safe = false; break; }
      if (element !== document.body && element !== document.documentElement &&
          /(hidden|clip|scroll|auto)/.test(style.overflow + style.overflowX + style.overflowY)) { safe = false; break; }
      element = element.parentElement;
    }
    if (!safe) continue;
    const range = document.createRange();
    range.selectNodeContents(node);
    const rects = Array.from(range.getClientRects()).filter(r => r.width > 0 && r.height > 0);
    if (!rects.length || !rects.every(r => r.left >= 0 && r.top >= 0 && r.right <= width && r.bottom <= height)) continue;
    // Reject obscured text. This is observation evidence, never automatic recap permission.
    if (!rects.every(r => {
      const top = document.elementFromPoint((r.left + r.right) / 2, (r.top + r.bottom) / 2);
      return top && (top === node.parentElement || node.parentElement.contains(top));
    })) continue;
    const path = [];
    let child = node;
    while (child !== document.body && child.parentNode) {
      path.unshift(Array.prototype.indexOf.call(child.parentNode.childNodes, child));
      child = child.parentNode;
    }
    if (child !== document.body || path.length > 64) continue;
    result.push({ path: path.join('/'), text: text });
  }
  return JSON.stringify({ href: location.pathname, nodes: result });
})();
