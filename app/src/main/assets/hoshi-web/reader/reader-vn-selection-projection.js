(function(global) {
  'use strict';

  function ReaderVnSelectionProjection(reader) {
    this.reader = reader;
  }

  ReaderVnSelectionProjection.prototype = {
    toSemanticHit: function(renderedHit) {
      if (!renderedHit || !renderedHit.node) return null;
      var chapterPosition = this.reader.rangeMap.chapterPositionForClone(
        renderedHit.node,
        renderedHit.offset
      );
      if (!chapterPosition) return null;
      return this.reader.contentStream.sourcePositionForRawOffset(chapterPosition.rawOffset);
    },

    sourceRubyForRenderedRuby: function(ruby) {
      var walker = ruby.ownerDocument.createTreeWalker(ruby, NodeFilter.SHOW_TEXT, {
        acceptNode: function(node) {
          return node.parentElement.closest('rt, rp') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
        }
      });
      var node;
      while (node = walker.nextNode()) {
        var source = this.toSemanticHit({ node: node, offset: 0 });
        var sourceRuby = source && source.node.parentElement.closest('ruby');
        if (sourceRuby) return sourceRuby;
      }
      return null;
    },

    normalizedOffsetForHit: function(semanticHit) {
      if (!semanticHit || !semanticHit.node) return null;
      return this.reader.contentStream.matchableOffsetForSourcePosition(
        semanticHit.node,
        semanticHit.offset
      );
    },

    visibleRangesForSemanticRanges: function(ranges) {
      var visibleRanges = [];
      for (var i = 0; i < ranges.length; i++) {
        var range = ranges[i];
        var start = this.reader.contentStream.rawOffsetForSourcePosition(
          range.node,
          range.start
        );
        var end = this.reader.contentStream.rawOffsetForSourcePosition(
          range.node,
          range.end
        );
        if (start === null || end === null || end < start) return [];
        var segments = this.reader.rangeMap.collectRawSegments(start, end - start);
        Array.prototype.push.apply(visibleRanges, segments);
      }
      return visibleRanges;
    }
  };

  global.hoshiReaderVnSelectionProjection = {
    create: function(reader) {
      return new ReaderVnSelectionProjection(reader);
    }
  };
})(window);
