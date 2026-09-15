(function(global) {
  'use strict';

  function pixelValue(value) {
    var parsed = parseFloat(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  function hasGeneratedContent(style) {
    var content = style && style.content;
    return !!content && content !== 'none' && content !== 'normal' && content !== '""' && content !== "''";
  }

  function isMeaningfulEmptySpan(element) {
    if (element.hasAttribute && (element.hasAttribute('id') || element.hasAttribute('name'))) {
      return true;
    }
    var style = global.getComputedStyle(element);
    if (style.backgroundImage && style.backgroundImage !== 'none') {
      return true;
    }
    return hasGeneratedContent(global.getComputedStyle(element, '::before'))
      || hasGeneratedContent(global.getComputedStyle(element, '::after'));
  }

  function sanitizeInlineBlocks(scope, vertical) {
    var doc = scope && scope.body ? scope : global.document;
    if (!doc || !doc.body || !doc.querySelectorAll || !global.getComputedStyle) return;

    var bodyStyle = global.getComputedStyle(doc.body);
    var blockExtent = vertical
      ? doc.body.clientWidth - pixelValue(bodyStyle.paddingLeft) - pixelValue(bodyStyle.paddingRight)
      : doc.body.clientHeight - pixelValue(bodyStyle.paddingTop) - pixelValue(bodyStyle.paddingBottom);
    var inlineExtent = vertical
      ? doc.body.clientHeight - pixelValue(bodyStyle.paddingTop) - pixelValue(bodyStyle.paddingBottom)
      : doc.body.clientWidth - pixelValue(bodyStyle.paddingLeft) - pixelValue(bodyStyle.paddingRight);

    function blockSize(element) {
      var rect = element.getBoundingClientRect();
      return vertical ? rect.width : rect.height;
    }

    function inlineSize(element) {
      var rect = element.getBoundingClientRect();
      return vertical ? rect.height : rect.width;
    }

    Array.from(doc.querySelectorAll('div, span')).forEach(function(element) {
      if (global.getComputedStyle(element).display !== 'inline-block' || !element.querySelector('p')) {
        return;
      }
      if (inlineSize(element) <= inlineExtent + 1 && blockSize(element) <= blockExtent + 1) {
        return;
      }
      element.style.setProperty('display', 'block', 'important');
      if (!element.parentNode || !element.parentNode.querySelectorAll) return;
      element.parentNode.querySelectorAll('span:empty').forEach(function(strut) {
        if (
          strut.parentNode === element.parentNode
          && !isMeaningfulEmptySpan(strut)
          && global.getComputedStyle(strut).display === 'inline-block'
        ) {
          strut.style.setProperty('display', 'none', 'important');
        }
      });
    });

    Array.from(doc.querySelectorAll('span:empty')).forEach(function(element) {
      if (
        !isMeaningfulEmptySpan(element)
        && global.getComputedStyle(element).display === 'inline-block'
        && blockSize(element) > blockExtent
      ) {
        element.style.setProperty(vertical ? 'width' : 'height', blockExtent + 'px', 'important');
      }
    });
  }

  global.hoshiReaderLayoutSemantics = {
    sanitizeInlineBlocks: sanitizeInlineBlocks
  };
})(window);
