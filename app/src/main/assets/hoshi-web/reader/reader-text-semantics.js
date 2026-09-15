(function(global) {
  'use strict';

  var ttuRegexNegated = /[^0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]+/gimu;
  var ttuRegex = /[0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]/iu;

  function normalizeText(text) {
    return String(text || '').replace(ttuRegexNegated, '');
  }

  function isMatchableChar(char) {
    return ttuRegex.test(char || '');
  }

  function isJapaneseBreakCharacter(text) {
    var code = (text || '').codePointAt(0);
    return (code >= 0x3000 && code <= 0x303f) ||
      (code >= 0x3040 && code <= 0x30ff) ||
      (code >= 0x3400 && code <= 0x9fff) ||
      (code >= 0xf900 && code <= 0xfaff) ||
      (code >= 0xff00 && code <= 0xffef);
  }

  function countChars(text) {
    return Array.from(normalizeText(text)).length;
  }

  function countRawChars(text) {
    return Array.from(text || '').length;
  }

  global.hoshiReaderTextSemantics = {
    normalizeText: normalizeText,
    isMatchableChar: isMatchableChar,
    isJapaneseBreakCharacter: isJapaneseBreakCharacter,
    countChars: countChars,
    countRawChars: countRawChars
  };
})(window);
