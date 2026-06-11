//
//  selection-en.js
//  Hoshi Reader
//
//  Copyright © 2026 Antimony.
//  SPDX-License-Identifier: GPL-3.0-or-later
//

(function() {
    const EnglishScanDelimiters = '"“”„‟\'‘’‚‛«»‹›!?—–-‐‑‒/\\|@#$%^&*_+=~`<>';
    const EnglishWordInternalDelimiters = '\'’`-‐‑';

    function isEnglishWordChar(char) {
        return !!char && /[A-Za-z0-9]/.test(char);
    }

    function isWordInternalDelimiter(text, offset) {
        const char = text[offset];
        return EnglishWordInternalDelimiters.includes(char) &&
            isEnglishWordChar(text[offset - 1]) &&
            isEnglishWordChar(text[offset + 1]);
    }

    function isEnglishScanBoundary(text, offset, selection) {
        const char = text[offset];
        return selection.scanDelimiters.includes(char) ||
            (EnglishScanDelimiters.includes(char) && !isWordInternalDelimiter(text, offset));
    }

    const EnglishSelectionLanguage = {
        isScanBoundary(char, selection) {
            return selection.scanDelimiters.includes(char) || EnglishScanDelimiters.includes(char);
        },

        isScanBoundaryAt(text, offset, selection) {
            return isEnglishScanBoundary(text, offset, selection);
        },

        isHitBoundary(char, selection) {
            return /^[\s\u3000]$/.test(char) || this.isScanBoundary(char, selection);
        },

        isHitBoundaryAt(text, offset, selection) {
            return /^[\s\u3000]$/.test(text[offset]) || isEnglishScanBoundary(text, offset, selection);
        },

        isWordStartBoundary(char, selection) {
            return this.isHitBoundary(char, selection);
        },

        isWordStartBoundaryAt(text, offset, selection) {
            return this.isHitBoundaryAt(text, offset, selection);
        },

        selectionStartForHit(hit, selection) {
            return selection.findWordStart(hit);
        },
    };

    window.hoshiSelectionLanguagePolicies = {
        ...window.hoshiSelectionLanguagePolicies,
        en: EnglishSelectionLanguage,
    };
})();
