import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const readerTextSemanticsUrl = new URL('../../main/assets/hoshi-web/reader/reader-text-semantics.js', import.meta.url);
const readerVnRangeMapUrl = new URL('../../main/assets/hoshi-web/reader/reader-vn-range-map.js', import.meta.url);

function loadRangeMap() {
    const window = {};
    vm.runInNewContext(
        [
            fs.readFileSync(readerTextSemanticsUrl, 'utf8'),
            fs.readFileSync(readerVnRangeMapUrl, 'utf8'),
        ].join('\n'),
        { window },
    );
    const semantics = window.hoshiReaderTextSemantics;
    const reader = {
        countChars: semantics.countChars.bind(semantics),
        countRawChars: semantics.countRawChars.bind(semantics),
    };
    return window.hoshiReaderVnRangeMap.create(reader);
}

test('VN range map converts clone UTF-16 positions to chapter offsets', () => {
    const rangeMap = loadRangeMap();
    const clone = { textContent: '𠮟激' };
    rangeMap.registerCloneTextOffset(clone, 10, 12);

    assert.deepEqual(
        JSON.parse(JSON.stringify(rangeMap.chapterPositionForClone(clone, 2))),
        { matchableOffset: 11, rawOffset: 13 },
    );
    assert.equal(rangeMap.chapterPositionForClone({ textContent: '外' }, 0), null);
});

test('VN clone positions count Korean while retaining raw punctuation and supplementary offsets', () => {
    const rangeMap = loadRangeMap();
    const clone = { textContent: '𠮟가、ㄱ한' };
    rangeMap.registerCloneTextOffset(clone, 10, 12);

    assert.deepEqual(
        JSON.parse(JSON.stringify(rangeMap.chapterPositionForClone(clone, 5))),
        { matchableOffset: 13, rawOffset: 16 },
    );
});

test('VN clips source-owned punctuation even when a screen starts with only the previous cue suffix', () => {
    const rangeMap = loadRangeMap();
    const source = '一……二。';
    // A screen split leaves the previous cue's ellipsis before the next cue.
    const clone = { textContent: '……二。' };
    const reader = rangeMap.reader;
    const window = {};
    vm.runInNewContext(fs.readFileSync(readerTextSemanticsUrl, 'utf8'), { window });
    const index = window.hoshiReaderTextSemantics.createSasayakiTextIndex([{ text: source }]);
    reader.contentStream = { sasayakiTextIndex: () => index };
    reader.nodeStartRawOffsets = new Map([[clone, 1]]);
    reader.createWalker = () => {
        let visited = false;
        return { nextNode: () => visited ? null : (visited = true, clone) };
    };
    const text = (ranges) => ranges.map(({ node, start, end }) => node.textContent.slice(start, end)).join('');
    assert.equal(text(rangeMap.collectMatchableSegments(0, 1)), '……');
    assert.equal(text(rangeMap.collectMatchableSegments(1, 2)), '二。');
    assert.equal(text(rangeMap.collectMatchableSegments(0, 2)), clone.textContent);
});
