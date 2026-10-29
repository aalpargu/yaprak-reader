(function () {
  // A short lived candidate for a single text node. This does not prove a
  // durable highlight anchor or that the selected passage has been read.
  try {
    if (!document.body || document.visibilityState !== 'visible') return null;
    const selection = window.getSelection();
    if (!selection || selection.rangeCount !== 1 || selection.isCollapsed) return null;
    const range = selection.getRangeAt(0);
    const node = range.startContainer;
    if (node !== range.endContainer || node.nodeType !== Node.TEXT_NODE ||
        !document.body.contains(node)) return null;

    const nodeText = node.nodeValue;
    const start = range.startOffset, end = range.endOffset;
    const boundary = offset => !(offset > 0 && offset < nodeText.length &&
      nodeText.charCodeAt(offset - 1) >= 0xD800 && nodeText.charCodeAt(offset - 1) <= 0xDBFF &&
      nodeText.charCodeAt(offset) >= 0xDC00 && nodeText.charCodeAt(offset) <= 0xDFFF);
    if (!nodeText || nodeText.length > 4096 || start < 0 ||
        end <= start || end > nodeText.length || !boundary(start) || !boundary(end) ||
        nodeText.slice(start, end) !== selection.toString()) return null;

    const path = [];
    let child = node;
    while (child !== document.body && child.parentNode) {
      const index = Array.prototype.indexOf.call(child.parentNode.childNodes, child);
      if (index < 0) return null;
      path.unshift(index);
      if (path.length > 64) return null;
      child = child.parentNode;
    }
    if (child !== document.body || path.join('/').length > 1024) return null;
    return JSON.stringify({ href: location.pathname, path: path.join('/'),
      start: start, end: end, nodeText: nodeText });
  } catch (_) {
    return null;
  }
})();
