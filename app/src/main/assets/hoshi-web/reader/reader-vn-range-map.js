(function(global) {
  'use strict';

  function codePointStartOffset(text, utf16Offset) {
    var bounded = Math.max(0, Math.min(text.length, Number(utf16Offset) || 0));
    if (
      bounded > 0 &&
      bounded < text.length &&
      text.charCodeAt(bounded) >= 0xdc00 &&
      text.charCodeAt(bounded) <= 0xdfff &&
      text.charCodeAt(bounded - 1) >= 0xd800 &&
      text.charCodeAt(bounded - 1) <= 0xdbff
    ) {
      return bounded - 1;
    }
    return bounded;
  }

  function ReaderVnRangeMap(reader) {
    this.reader = reader;
    this.cloneTextOffsets = new WeakMap();
    this.cloneTextRawOffsets = new WeakMap();
  }

  ReaderVnRangeMap.prototype = {
    registerCloneTextOffset: function(node, charOffset, rawOffset) {
      this.cloneTextOffsets.set(node, charOffset === undefined ? 0 : charOffset);
      this.cloneTextRawOffsets.set(node, rawOffset === undefined ? 0 : rawOffset);
    },

    cloneTextOffsetForNode: function(node) {
      return this.cloneTextOffsets.get(node);
    },

    cloneTextRawOffsetForNode: function(node) {
      return this.cloneTextRawOffsets.get(node);
    },

    chapterPositionForClone: function(node, utf16Offset) {
      var charBase = this.cloneTextOffsets.get(node);
      var rawBase = this.cloneTextRawOffsets.get(node);
      if (charBase === undefined || rawBase === undefined || !node) return null;
      var text = String(node.nodeValue || node.textContent || '');
      var offset = codePointStartOffset(text, utf16Offset);
      var prefix = text.slice(0, offset);
      return {
        matchableOffset: charBase + this.reader.countChars(prefix),
        rawOffset: rawBase + this.reader.countRawChars(prefix)
      };
    },

    collectRawSegments: function(offset, length) {
      var start = Number(offset) || 0;
      var end = start + Math.max(0, Number(length) || 0);
      var segments = [];
      var walker = this.reader.createWalker();
      var node;
      while (node = walker.nextNode()) {
        var nodeStart = this.reader.nodeStartRawOffsets.get(node);
        if (nodeStart === undefined) continue;
        var text = node.textContent || '';
        var rawCursor = nodeStart;
        var i = 0;
        var segment = null;
        var flushSegment = function() {
          if (!segment) return;
          segments.push(segment);
          segment = null;
        };
        while (i < text.length && rawCursor < end) {
          var char = String.fromCodePoint(text.codePointAt(i));
          var next = i + char.length;
          if (rawCursor >= start) {
            if (!segment) {
              segment = { node: node, start: i, end: next };
            } else {
              segment.end = next;
            }
          }
          rawCursor += 1;
          i = next;
        }
        flushSegment();
      }
      return segments;
    },

    collectMatchableCueRanges: function(cues) {
      var result = [];
      for (var i = 0; i < cues.length; i++) {
        var cue = cues[i];
        if (!cue || !cue.id) continue;
        var start = Math.max(0, Number(cue.start) || 0);
        var length = Math.max(0, Number(cue.length) || 0);
        result.push({
          id: cue.id,
          ranges: this.collectMatchableSegments(start, start + length)
        });
      }
      return result;
    },

    collectMatchableSegments: function(startOffset, endOffset) {
      var stream = this.reader.contentStream;
      if (!stream) return [];
      var range = stream.sasayakiTextIndex().range(startOffset, Number(endOffset) - Number(startOffset));
      return range ? this.collectRawSegments(range.start, range.end - range.start) : [];
    }
  };

  global.hoshiReaderVnRangeMap = {
    create: function(reader) {
      return new ReaderVnRangeMap(reader);
    }
  };
})(window);
