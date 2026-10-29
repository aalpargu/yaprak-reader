(function () {
  'use strict';
  // A complete live body is compared with original XHTML before these paths are trusted.
  // Only selected slices and hashes may become durable records; this inventory is transient.
  try {
    const owner = /*YAPRAK_SELECTION_OWNER*/;
    const mode = /*YAPRAK_SELECTION_MODE*/;
    if (mode !== 'capture' && mode !== 'identity') return null;
    if (!/^[a-f0-9-]{36}$/.test(owner) || !document.body || document.visibilityState !== 'visible') return null;
    const key = '__yaprak_exact_selection_v2_' + owner.replace(/-/g, '');
    let property = Object.getOwnPropertyDescriptor(window, key);
    if (!property) {
      const bytes = new Uint8Array(16);
      crypto.getRandomValues(bytes);
      bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128;
      const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
      const documentToken = hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
      let mutationGeneration = 0, selectionGeneration = 0, last = null;
      const bumpMutation = records => { if (records.length) mutationGeneration++; };
      const observer = new MutationObserver(bumpMutation);
      observer.observe(document.documentElement, { subtree: true, childList: true, characterData: true, attributes: true });
      document.addEventListener('selectionchange', () => { selectionGeneration++; });
      const capture = function (identityOnly) {
        bumpMutation(observer.takeRecords());
        if (!Number.isSafeInteger(mutationGeneration) || !Number.isSafeInteger(selectionGeneration) ||
            document.visibilityState !== 'visible' || !document.body) return null;
        const selection = window.getSelection();
        if (!selection || selection.rangeCount !== 1 || selection.isCollapsed) { last = null; return null; }
        const range = selection.getRangeAt(0), body = document.body;
        if (range.startContainer.nodeType !== Node.TEXT_NODE || range.endContainer.nodeType !== Node.TEXT_NODE ||
            !body.contains(range.startContainer) || !body.contains(range.endContainer)) return null;
        const identity = [body, range, range.startContainer, range.startOffset, range.endContainer, range.endOffset,
          selection.anchorNode, selection.anchorOffset, selection.focusNode, selection.focusOffset];
        if (!last || identity.some((value, index) => value !== last[index])) selectionGeneration++;
        last = identity;
        const preview = selection.toString();
        if (!preview.trim() || preview.length > 5000) return null;
        const selectionToken = documentToken + ':' + mutationGeneration + ':' + selectionGeneration;
        if (identityOnly) return JSON.stringify({ version: 2, href: location.pathname, preview, selectionToken });
        if (preview.length > 4096) return null;
        const boundary = (text, offset) => !(offset > 0 && offset < text.length &&
          text.charCodeAt(offset - 1) >= 0xD800 && text.charCodeAt(offset - 1) <= 0xDBFF &&
          text.charCodeAt(offset) >= 0xDC00 && text.charCodeAt(offset) <= 0xDFFF);
        const pending = [{ node: body, path: '', depth: 0 }], topology = [], texts = [];
        let budget = 2;
        while (pending.length) {
          const item = pending.pop(), node = item.node;
          if (topology.length >= 200000 || item.depth > 64 || item.path.length > 1024) return null;
          let entry;
          if (node.nodeType === Node.ELEMENT_NODE) entry = [1, node.namespaceURI || '', node.localName, node.childNodes.length];
          else if (node.nodeType === Node.TEXT_NODE) { entry = [3, node.nodeValue]; texts.push({ node, path: item.path }); }
          else if (node.nodeType === Node.COMMENT_NODE) entry = [8, node.nodeValue];
          else return null;
          budget += JSON.stringify(entry).length + 1;
          if (budget > 2097152) return null;
          topology.push(entry);
          for (let index = node.childNodes.length - 1; index >= 0; index--)
            pending.push({ node: node.childNodes[index], path: item.path ? item.path + '/' + index : String(index), depth: item.depth + 1 });
        }
        const first = texts.findIndex(item => item.node === range.startContainer);
        const end = texts.findIndex(item => item.node === range.endContainer);
        if (first < 0 || end < first || end - first + 1 > 64) return null;
        const segments = []; let total = 0;
        for (let index = first; index <= end; index++) {
          const item = texts[index], text = item.node.nodeValue;
          const start = index === first ? range.startOffset : 0;
          const finish = index === end ? range.endOffset : text.length;
          if (!text || text.length > 4096 || start < 0 || finish <= start || finish > text.length ||
              !boundary(text, start) || !boundary(text, finish)) return null;
          total += finish - start;
          if (total > 4096) return null;
          segments.push({ path: item.path, start, end: finish, nodeText: text });
        }
        const result = JSON.stringify({ version: 2, href: location.pathname, preview,
          selectionToken,
          segments, bodyTopology: topology });
        // WebView additionally JSON-quotes a returned string; bound that bridge payload too.
        if (new TextEncoder().encode(JSON.stringify(result)).length > 2097152) return null;
        return result;
      };
      Object.defineProperty(window, key, { value: capture, writable: false, configurable: false, enumerable: false });
      property = Object.getOwnPropertyDescriptor(window, key);
    }
    if (!property || typeof property.value !== 'function' || property.writable || property.configurable || property.enumerable) return null;
    return property.value(mode === 'identity');
  } catch (_) { return null; }
})();
