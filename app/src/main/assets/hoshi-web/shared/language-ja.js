//
//  language-ja.js
//  Hoshi Reader
//
//  Copyright © 2026 Manhhao.
//  Copyright © 2026 Antimony.
//  Copyright © 2024-2026 Yomitan Authors.
//  SPDX-License-Identifier: GPL-3.0-or-later
//

(function() {
    // https://github.com/yomidevs/yomitan/blob/ddbe4a2c0bf778583b38962d4b0b85442dfa8f6a/ext/js/language/CJK-util.js#L19
    const CJK_UNIFIED_IDEOGRAPHS_RANGE = [0x4e00, 0x9fff];
    const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A_RANGE = [0x3400, 0x4dbf];
    const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B_RANGE = [0x20000, 0x2a6df];
    const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C_RANGE = [0x2a700, 0x2b73f];
    const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D_RANGE = [0x2b740, 0x2b81f];
    const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E_RANGE = [0x2b820, 0x2ceaf];
    const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F_RANGE = [0x2ceb0, 0x2ebef];
    const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_G_RANGE = [0x30000, 0x3134f];
    const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_H_RANGE = [0x31350, 0x323af];
    const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_I_RANGE = [0x2ebf0, 0x2ee5f];
    const CJK_COMPATIBILITY_IDEOGRAPHS_RANGE = [0xf900, 0xfaff];
    const CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT_RANGE = [0x2f800, 0x2fa1f];
    const CJK_IDEOGRAPH_RANGES = [
        CJK_UNIFIED_IDEOGRAPHS_RANGE,
        CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A_RANGE,
        CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B_RANGE,
        CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C_RANGE,
        CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D_RANGE,
        CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E_RANGE,
        CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F_RANGE,
        CJK_UNIFIED_IDEOGRAPHS_EXTENSION_G_RANGE,
        CJK_UNIFIED_IDEOGRAPHS_EXTENSION_H_RANGE,
        CJK_UNIFIED_IDEOGRAPHS_EXTENSION_I_RANGE,
        CJK_COMPATIBILITY_IDEOGRAPHS_RANGE,
        CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT_RANGE,
    ];

    // https://github.com/yomidevs/yomitan/blob/ddbe4a2c0bf778583b38962d4b0b85442dfa8f6a/ext/js/language/CJK-util.js#L60
    const FULLWIDTH_CHARACTER_RANGES = [
        [0xff10, 0xff19],
        [0xff21, 0xff3a],
        [0xff41, 0xff5a],
        [0xff01, 0xff0f],
        [0xff1a, 0xff1f],
        [0xff3b, 0xff3f],
        [0xff5b, 0xff60],
        [0xffe0, 0xffee],
    ];

    // https://github.com/yomidevs/yomitan/blob/ddbe4a2c0bf778583b38962d4b0b85442dfa8f6a/ext/js/language/ja/japanese.js#L44
    const JAPANESE_RANGES = [
        [0x3040, 0x309f],
        [0x30a0, 0x30ff],
        ...CJK_IDEOGRAPH_RANGES,
        [0xff66, 0xff9f],
        [0x30fb, 0x30fc],
        [0xff61, 0xff65],
        [0x3000, 0x303f],
        ...FULLWIDTH_CHARACTER_RANGES,
    ];

    function isCodePointJapanese(codePoint) {
        return JAPANESE_RANGES.some(([start, end]) => codePoint >= start && codePoint <= end);
    }

    window.hoshiLanguageUtilities = {
        ...window.hoshiLanguageUtilities,
        ja: {
            isCodePointJapanese,
        },
    };
})();
