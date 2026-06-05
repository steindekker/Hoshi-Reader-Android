window.hoshiHighlights = {
  wrappers: new Map(),
  pendingRange: null,
  prepareHighlightSelection: function() {
    var selection = window.getSelection();
    if (!selection || selection.rangeCount === 0) return false;
    var range = selection.getRangeAt(0);
    if (range.collapsed) return false;
    this.pendingRange = range.cloneRange();
    return true;
  },
  createHighlight: function(color, id) {
    var selection = window.getSelection();
    var range = selection && selection.rangeCount > 0 ? selection.getRangeAt(0) : null;
    if ((!range || range.collapsed) && this.pendingRange) {
      range = this.pendingRange;
    }
    if (!range || range.collapsed) {
      this.pendingRange = null;
      return null;
    }
    var startPrefix = range.startContainer.textContent.substring(0, range.startOffset);
    var endPrefix = range.endContainer.textContent.substring(0, range.endOffset);
    var start = (window.hoshiReader.nodeStartOffsets.get(range.startContainer) || 0) + window.hoshiReader.countChars(startPrefix);
    var rawStart = (window.hoshiReader.nodeStartRawOffsets.get(range.startContainer) || 0) + window.hoshiReader.countRawChars(startPrefix);
    var rawEnd = (window.hoshiReader.nodeStartRawOffsets.get(range.endContainer) || 0) + window.hoshiReader.countRawChars(endPrefix);
    if (rawEnd <= rawStart) {
      this.pendingRange = null;
      return null;
    }
    var fragment = range.cloneContents();
    fragment.querySelectorAll('rt, rp').forEach(function(el) { el.remove(); });
    var text = fragment.textContent || '';
    if (selection) selection.removeAllRanges();
    this.pendingRange = null;
    this.wrapHighlight({ id: id, color: color, offset: rawStart, text: text });
    window.hoshiReader.buildNodeOffsets();
    requestAnimationFrame(function() {
      document.body.style.transform = 'translateZ(0)';
      requestAnimationFrame(function() { document.body.style.transform = ''; });
    });
    return { start: start, offset: rawStart, text: text };
  },
  collectSegments: function(offset, length) {
    var end = offset + length;
    var segments = [];
    var cursor = 0;
    var segment = null;
    var flushSegment = function() {
      if (!segment) return;
      segments.push(segment);
      segment = null;
    };
    var walker = window.hoshiReader.createWalker();
    var node;
    while (cursor < end && (node = walker.nextNode())) {
      var text = node.textContent || '';
      var i = 0;
      while (i < text.length && cursor < end) {
        var char = String.fromCodePoint(text.codePointAt(i));
        var next = i + char.length;
        if (cursor >= offset) {
          if (!segment || segment.node !== node) {
            flushSegment();
            segment = { node: node, start: i, end: next };
          } else {
            segment.end = next;
          }
        }
        cursor += 1;
        i = next;
      }
      flushSegment();
    }
    return segments;
  },
  wrapHighlight: function(highlight) {
    var segments = this.collectSegments(highlight.offset, Array.from(highlight.text || '').length);
    if (!segments.length) return;
    var range = document.createRange();
    var wrappers = [];
    for (var i = segments.length - 1; i >= 0; i--) {
      var segment = segments[i];
      range.setStart(segment.node, segment.start);
      range.setEnd(segment.node, segment.end);
      var wrapper = document.createElement('span');
      wrapper.className = 'hoshi-highlight hoshi-highlight-' + highlight.color;
      wrapper.appendChild(range.extractContents());
      range.insertNode(wrapper);
      wrappers.push(wrapper);
    }
    wrappers.reverse();
    this.wrappers.set(highlight.id, wrappers);
  },
  applyHighlights: function(highlights) {
    for (var i = 0; i < highlights.length; i++) {
      this.wrapHighlight(highlights[i]);
    }
    window.hoshiReader.buildNodeOffsets();
  },
  removeHighlight: function(id) {
    var wrappers = this.wrappers.get(id);
    if (!wrappers) return;
    window.hoshiReader.unwrap(wrappers);
    this.wrappers.delete(id);
    window.hoshiReader.buildNodeOffsets();
    requestAnimationFrame(function() {
      document.body.style.transform = 'translateZ(0)';
      requestAnimationFrame(function() { document.body.style.transform = ''; });
    });
  }
};
