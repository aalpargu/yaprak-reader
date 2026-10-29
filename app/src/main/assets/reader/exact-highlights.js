(function (request) {
  'use strict';
  // Native StaticRange paint only. Never insert wrappers into publication body or resolve quotes fuzzily.
  const answer = (state, extra) => JSON.stringify(Object.assign({ state, owner: request.owner,
    sourceHash: request.sourceHash, generation: request.generation, path: location.pathname }, extra || {}));
  const colors = { YELLOW: 'rgb(255, 220, 40)', GREEN: 'rgb(110, 220, 145)',
    BLUE: 'rgb(125, 190, 255)', PINK: 'rgb(255, 155, 205)' };
  const validBoundary = (text, offset) => Number.isInteger(offset) && offset >= 0 && offset <= text.length &&
    !(offset > 0 && offset < text.length && text.charCodeAt(offset - 1) >= 0xD800 &&
      text.charCodeAt(offset - 1) <= 0xDBFF && text.charCodeAt(offset) >= 0xDC00 && text.charCodeAt(offset) <= 0xDFFF);
  const utf8Size = value => {
    let size = 0;
    for (let i = 0; i < value.length; i++) {
      const code = value.charCodeAt(i);
      if (code < 0x80) size++;
      else if (code < 0x800) size += 2;
      else if (code >= 0xD800 && code <= 0xDBFF && i + 1 < value.length &&
          value.charCodeAt(i + 1) >= 0xDC00 && value.charCodeAt(i + 1) <= 0xDFFF) { size += 4; i++; }
      else size += 3;
    }
    return size;
  };
  const sameEntry = (left, right) => Array.isArray(left) && left.length === right.length &&
    left.every((value, index) => value === right[index]);
  const validPath = path => typeof path === 'string' && path.length <= 1024 &&
    /^(?:0|[1-9][0-9]*)(?:\/(?:0|[1-9][0-9]*)){0,63}$/.test(path) &&
    path.split('/').every(part => Number.isSafeInteger(Number(part)) && Number(part) <= 200000);
  const validRevision = revision => typeof revision === 'string' && /^(?:0|[1-9][0-9]{0,18})$/.test(revision) &&
    (revision.length < 19 || revision <= '9223372036854775807');
  let owned = null;
  try {
    if (!request || !/^[a-z0-9_]{1,80}$/.test(request.owner) ||
        !Number.isSafeInteger(request.generation) || request.generation < 0 ||
        !/^[0-9a-f]{64}$/.test(request.sourceHash)) return null;
    const key = '__' + request.owner;
    owned = window[key];
    if (request.op === 'clear') {
      if (owned && owned.owner === request.owner && request.generation >= owned.generation) {
        owned.clear(); owned.generation = request.generation; owned.phase = 'cleared';
      }
      return answer('cleared');
    }
    if (location.pathname !== request.path || !document.body || !document.head) return answer('stale');
    const supported = typeof CSS !== 'undefined' && CSS.highlights &&
      typeof CSS.highlights.get === 'function' && typeof CSS.highlights.set === 'function' &&
      typeof CSS.highlights.delete === 'function' && typeof CSS.highlights.has === 'function' && typeof Highlight === 'function' &&
      typeof StaticRange === 'function' && CSS.supports('selector(::highlight(yaprak_support))');
    if (!supported) return answer('unsupported');
    if (owned && (owned.owner !== request.owner || owned.sourceHash !== request.sourceHash)) return answer('failed');
    if (request.op === 'begin') {
      if (owned && request.generation < owned.generation) return answer('stale');
      if (!owned) {
        const names = Object.keys(colors).map(color => request.owner + '_' + color.toLowerCase());
        // A publisher's registry/style collision is never overwritten, even with an unlikely random name.
        if (names.some(name => CSS.highlights.has(name)) || document.getElementById(request.owner)) return answer('failed');
        const style = document.createElement('style');
        style.id = request.owner;
        const css = Object.keys(colors).map(color => '::highlight(' + request.owner + '_' + color.toLowerCase() +
          '){background-color:' + colors[color] + ';color:rgb(20,20,20)}').join('\n');
        style.textContent = css;
        document.head.appendChild(style);
        const sheetWorks = () => {
          try {
            if (!style.isConnected || style.parentNode !== document.head || style.textContent !== css ||
                !style.sheet || style.sheet.cssRules.length !== 4) return false;
            // CSSOM writes need not change style.textContent or generate a DOM mutation.
            return Object.keys(colors).every((color, index) => {
              const rule = style.sheet.cssRules[index];
              return rule.selectorText === '::highlight(' + request.owner + '_' + color.toLowerCase() + ')' &&
                rule.style.length === 2 && rule.style.getPropertyValue('background-color') === colors[color] &&
                rule.style.getPropertyValue('color') === 'rgb(20, 20, 20)' &&
                !rule.style.getPropertyPriority('background-color') && !rule.style.getPropertyPriority('color');
            });
          } catch (_) { return false; }
        };
        if (!sheetWorks()) { style.remove(); return answer('failed'); } // Publisher CSP may reject inline style.
        const groups = new Map();
        if (typeof request.documentSeed !== 'string' || !/^[a-z0-9-]{36}$/.test(request.documentSeed)) {
          style.remove(); return answer('failed');
        }
        owned = { owner: request.owner, sourceHash: request.sourceHash, generation: request.generation, token: request.documentSeed,
          phase: 'begun', total: 0, resolved: 0, groups, sheetWorks, style,
          clear() {
            for (const [name, value] of groups) {
              try { if (CSS.highlights.get(name) === value) CSS.highlights.delete(name); } catch (_) { /* own registry only */ }
            }
            groups.clear();
          } };
        const observer = new MutationObserver(() => {
          owned.clear(); owned.phase = 'mutated'; owned.resolved = 0;
        });
        // Observe the stable document root as well: a publisher can replace the entire body.
        // Attributes are deliberately excluded; font, theme and column reflow use native paint.
        observer.observe(document.documentElement, { subtree: true, childList: true, characterData: true });
        owned.observer = observer;
        window[key] = owned;
      }
      if (!owned.sheetWorks()) { owned.clear(); return answer('failed'); }
      owned.clear(); owned.generation = request.generation; owned.phase = 'begun';
      owned.total = 0; owned.resolved = 0;
      return answer('begun', { documentToken: owned.token });
    }
    if (!owned || owned.generation !== request.generation || owned.token !== request.documentToken)
      return answer('stale');
    if (!owned.sheetWorks() || Array.from(owned.groups).some(([name, value]) => CSS.highlights.get(name) !== value)) {
      owned.clear(); owned.phase = 'failed'; return answer('failed', { documentToken: owned.token });
    }
    if (request.op === 'status') return answer(owned.phase, { documentToken: owned.token,
      total: owned.total, resolved: owned.resolved });
    if (request.op !== 'apply' || owned.phase !== 'begun') return answer('stale');
    owned.clear();
    if (utf8Size(JSON.stringify(request)) > 2 * 1024 * 1024) return answer('limit');
    const multiGroups = request.groups === undefined ? [] : request.groups;
    const rangeNodes = request.rangeNodes === undefined ? [] : request.rangeNodes;
    if (!Array.isArray(request.nodes) || !Array.isArray(request.ranges) || request.ranges.length > 1000 ||
        !Array.isArray(multiGroups) || multiGroups.length > 1000 || !Array.isArray(rangeNodes) || rangeNodes.length > 64000 ||
        !Number.isInteger(request.total) || request.total < request.ranges.length + multiGroups.length || request.total > 1000)
      return answer('failed');
    const byText = new Map();
    let payloadUnits = 0;
    for (let i = 0; i < request.nodes.length; i++) {
      const text = request.nodes[i];
      if (typeof text !== 'string' || !text.length || text.length > 4096 || byText.has(text)) return answer('failed');
      payloadUnits += text.length;
      if (payloadUnits > 2 * 1024 * 1024) return answer('limit');
      byText.set(text, { count: 0, node: null, index: i });
    }
    const byPath = new Map();
    for (const sourceNode of rangeNodes) {
      if (!sourceNode || !validPath(sourceNode.path) || typeof sourceNode.text !== 'string' ||
          !sourceNode.text.length || sourceNode.text.length > 4096 || byPath.has(sourceNode.path)) return answer('failed');
      payloadUnits += sourceNode.text.length;
      if (payloadUnits > 2 * 1024 * 1024) return answer('limit');
      byPath.set(sourceNode.path, sourceNode.text);
    }
    if (!request.ranges.length && !multiGroups.length) {
      // No live range can be painted. Avoid scanning an entire chapter on every pager load
      // when it has no colored annotations, or when original-source proof resolved none.
      owned.total = request.total; owned.resolved = 0;
      owned.phase = request.total === 0 ? 'ready' : 'partial';
      return answer(owned.phase, { documentToken: owned.token, total: owned.total, resolved: 0 });
    }
    const needsTopology = multiGroups.length > 0;
    if (needsTopology && (!Array.isArray(request.bodyTopology) || !request.bodyTopology.length)) return answer('failed');
    if (needsTopology && request.bodyTopology.length > 200000) return answer('limit');
    let topologyMatches = true, topologyIndex = 0, topologyBytes = 2, topologyUnits = 0;
    const checkTopology = node => {
      let entry;
      if (node.nodeType === Node.ELEMENT_NODE) {
        const namespace = node.namespaceURI || '', name = node.localName;
        if (namespace.length > 4096 || !name || name.length > 4096) return 'limit';
        entry = [1, namespace, name, node.childNodes.length];
      } else if (node.nodeType === Node.TEXT_NODE || node.nodeType === Node.COMMENT_NODE) {
        const text = node.nodeValue || '';
        topologyUnits += text.length;
        if (topologyUnits > 2 * 1024 * 1024) return 'limit';
        entry = [node.nodeType, text];
      } else {
        // Do not merge CDATA, processing instructions or other XML/HTML parser differences.
        topologyMatches = false; topologyIndex++; return null;
      }
      topologyBytes += utf8Size(JSON.stringify(entry)) + (topologyIndex ? 1 : 0);
      if (topologyBytes > 2 * 1024 * 1024) return 'limit';
      if (!sameEntry(request.bodyTopology[topologyIndex], entry)) topologyMatches = false;
      topologyIndex++;
      return null;
    };
    if (needsTopology && checkTopology(document.body) === 'limit') return answer('limit');
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ALL);
    const textOrdinals = new Map();
    let visited = 1, units = 0, ordinal = 0, node;
    while ((node = walker.nextNode())) {
      if (++visited > 200000) return answer('limit');
      if (needsTopology && checkTopology(node) === 'limit') return answer('limit');
      if (node.nodeType !== Node.TEXT_NODE) continue;
      if (needsTopology) textOrdinals.set(node, ordinal++);
      units += node.data.length;
      if (units > 2 * 1024 * 1024) return answer('limit');
      const matched = byText.get(node.data);
      if (matched) { matched.count++; matched.node = node; }
    }
    if (needsTopology && topologyIndex !== request.bodyTopology.length) topologyMatches = false;
    // Complete scan finished; duplicates anywhere in body fail closed, including hidden clones.
    const candidates = new Map(Object.keys(colors).map(color => [color, []]));
    const ids = new Set();
    let resolved = 0;
    for (const range of request.ranges) {
      if (!range || typeof range.id !== 'string' || !range.id.length || range.id.length > 128 || ids.has(range.id) ||
          !Number.isInteger(range.node) || range.node < 0 || range.node >= request.nodes.length ||
          typeof range.text !== 'string' || !Object.prototype.hasOwnProperty.call(colors, range.color)) return answer('failed');
      ids.add(range.id);
      const text = request.nodes[range.node], matched = byText.get(text);
      if (!matched || matched.count !== 1 || !validBoundary(text, range.start) || !validBoundary(text, range.end) ||
          range.end <= range.start || text.slice(range.start, range.end) !== range.text) continue;
      const exact = new StaticRange({ startContainer: matched.node, startOffset: range.start,
        endContainer: matched.node, endOffset: range.end });
      candidates.get(range.color).push(exact); resolved++;
    }
    for (const group of multiGroups) {
      if (!group || typeof group.id !== 'string' || group.id.length > 128 || !group.id.length || ids.has(group.id) ||
          !validRevision(group.revision) ||
          !Object.prototype.hasOwnProperty.call(colors, group.color) || !Array.isArray(group.segments) ||
          !group.segments.length || group.segments.length > 64) return answer('failed');
      ids.add(group.id);
      // Paths cannot address a live publication until the complete typed body tree matches
      // the original source. A publisher/Readium-added subtree is not exempted by its ID.
      if (!topologyMatches) continue;
      const prepared = [], paths = new Set();
      let complete = topologyMatches, selectedUnits = 0, previous = null;
      for (let index = 0; index < group.segments.length; index++) {
        const segment = group.segments[index];
        if (!segment || !Number.isInteger(segment.node) || segment.node < 0 || segment.node >= rangeNodes.length ||
            typeof segment.text !== 'string') return answer('failed');
        const proof = rangeNodes[segment.node], text = proof.text;
        if (paths.has(proof.path)) return answer('failed');
        paths.add(proof.path);
        selectedUnits += segment.text.length;
        if (selectedUnits > 4096) return answer('failed');
        if (!validBoundary(text, segment.start) || !validBoundary(text, segment.end) || segment.end <= segment.start ||
            text.slice(segment.start, segment.end) !== segment.text ||
            (index > 0 && segment.start !== 0) || (index < group.segments.length - 1 && segment.end !== text.length)) complete = false;
        let matched = document.body;
        for (const part of proof.path.split('/')) {
          const child = Number(part);
          matched = Number.isSafeInteger(child) && matched ? matched.childNodes[child] : null;
        }
        if (!matched || matched.nodeType !== Node.TEXT_NODE || matched.nodeValue !== text ||
            (previous && !(previous.compareDocumentPosition(matched) & Node.DOCUMENT_POSITION_FOLLOWING))) complete = false;
        if (matched && matched.nodeType === Node.TEXT_NODE) {
          // Constant-time adjacency after one bounded complete body scan. This avoids a
          // full chapter walk per segment when hundreds of overlapping groups are painted.
          if (previous && textOrdinals.get(matched) !== textOrdinals.get(previous) + 1) complete = false;
          previous = matched;
          if (validBoundary(text, segment.start) && validBoundary(text, segment.end) && segment.end > segment.start)
            prepared.push(new StaticRange({ startContainer: matched, startOffset: segment.start,
              endContainer: matched, endOffset: segment.end }));
        }
      }
      // Registry writes happen only after every group is prepared. One bad segment paints none of its group.
      if (complete && prepared.length === group.segments.length) {
        candidates.get(group.color).push(...prepared); resolved++;
      }
    }
    try {
      for (const [color, ranges] of candidates) {
        if (!ranges.length) continue;
        const name = request.owner + '_' + color.toLowerCase();
        if (CSS.highlights.has(name)) throw Error('Owned namespace collision');
        const value = new Highlight(...ranges);
        owned.groups.set(name, value); // Include it before set so a throwing setter can be cleaned up.
        CSS.highlights.set(name, value);
        if (CSS.highlights.get(name) !== value) throw Error('Registry rejected highlight');
      }
    } catch (_) { owned.clear(); owned.phase = 'failed'; return answer('failed'); }
    owned.total = request.total; owned.resolved = resolved;
    owned.phase = resolved === request.total ? 'ready' : 'partial';
    return answer(owned.phase, { documentToken: owned.token, total: owned.total, resolved });
  } catch (_) {
    try { if (owned) { owned.clear(); owned.phase = 'failed'; } } catch (_) { /* best-effort own cleanup */ }
    return answer('failed');
  }
})(/*YAPRAK_REQUEST*/);
