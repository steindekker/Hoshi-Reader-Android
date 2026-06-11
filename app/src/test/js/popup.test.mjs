import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const popupSourceUrl = new URL('../../main/assets/hoshi-web/popup/popup.js', import.meta.url);
const japaneseLanguageUrl = new URL('../../main/assets/hoshi-web/shared/language-ja.js', import.meta.url);
const japaneseSelectionUrl = new URL('../../main/assets/hoshi-web/shared/selection-ja.js', import.meta.url);
const sharedSelectionUrl = new URL('../../main/assets/hoshi-web/shared/selection.js', import.meta.url);

class FakeContainer {
    constructor() {
        this.listeners = new Map();
        this.clickAttached = false;
    }

    addEventListener(type, listener) {
        const listeners = this.listeners.get(type) ?? [];
        listeners.push(listener);
        this.listeners.set(type, listeners);
    }

    dispatch(type, event) {
        (this.listeners.get(type) ?? []).forEach((listener) => listener(event));
    }
}

class FakeElement {
    constructor(matches = [], tagName = 'div') {
        this.attributes = new Map();
        this.children = [];
        this.className = '';
        this.dataset = {};
        this.matches = new Set(matches);
        this.nodeType = 1;
        this.parentElement = null;
        this.textContent = '';
        this.style = {
            properties: new Map(),
            setProperty(name, value) {
                this.properties.set(name, value);
            },
        };
        this.tagName = tagName.toUpperCase();
    }

    setAttribute(name, value) {
        const stringValue = String(value);
        this.attributes.set(name, stringValue);
        if (name.startsWith('data-')) {
            const dataKey = name.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
            this.dataset[dataKey] = stringValue;
        }
    }

    getAttribute(name) {
        return this.attributes.get(name) ?? null;
    }

    appendChild(child) {
        child.parentElement = this;
        this.children.push(child);
        return child;
    }

    append(...children) {
        children.forEach((child) => this.appendChild(child));
    }

    addEventListener(type, listener) {
        const listeners = this.listeners?.get(type) ?? [];
        listeners.push(listener);
        this.listeners ??= new Map();
        this.listeners.set(type, listeners);
    }

    closest(selector) {
        const selectors = selector.split(',').map((item) => item.trim());
        return selectors.some((item) => this.matches.has(item) || item === this.tagName.toLowerCase()) ? this : null;
    }

    remove() {}
}

function popupContext({ loadJapaneseLanguageAsset = false, loadSelectionAssets = false } = {}) {
    const documentElement = new FakeElement();
    const body = new FakeContainer();
    body.appendChild = function(element) {
        return element;
    };
    const documentListeners = new Map();
    const document = {
        body,
        documentElement,
        addEventListener(type, listener) {
            const listeners = documentListeners.get(type) ?? [];
            listeners.push(listener);
            documentListeners.set(type, listeners);
        },
        dispatch(type, event) {
            (documentListeners.get(type) ?? []).forEach((listener) => listener(event));
        },
        createElement(tagName) {
            return new FakeElement([], tagName);
        },
        querySelectorAll() {
            return [];
        },
    };
    const selectTextCalls = [];
    const tapOutsideMessages = [];
    const mineEntryMessages = [];
    const window = {
        scrollX: 0,
        scrollY: 0,
        scanLength: 24,
        addEventListener() {},
        hoshiSelection: {
            selectText(...args) {
                selectTextCalls.push(args);
                return '位置';
            },
        },
    };
    const context = {
        console,
        document,
        getComputedStyle() {
            return { zoom: '1' };
        },
        Node: { TEXT_NODE: 3 },
        webkit: {
            messageHandlers: {
                tapOutside: {
                    postMessage(message) {
                        tapOutsideMessages.push(message);
                    },
                },
                mineEntry: {
                    postMessage(message) {
                        mineEntryMessages.push(message);
                        return true;
                    },
                },
            },
        },
        window,
    };
    if (loadJapaneseLanguageAsset) {
        vm.runInNewContext(fs.readFileSync(japaneseLanguageUrl, 'utf8'), context);
    }
    if (loadSelectionAssets) {
        vm.runInNewContext(fs.readFileSync(japaneseSelectionUrl, 'utf8'), context);
        vm.runInNewContext(fs.readFileSync(sharedSelectionUrl, 'utf8'), context);
    }
    vm.runInNewContext(fs.readFileSync(popupSourceUrl, 'utf8'), context);
    return {
        context,
        body,
        document,
        selectTextCalls,
        tapOutsideMessages,
        mineEntryMessages,
    };
}

function touchEvent(target, x, y, cancelable = false) {
    return {
        target,
        touches: [{ clientX: x, clientY: y }],
        changedTouches: [{ clientX: x, clientY: y }],
        cancelable,
        defaultPrevented: false,
        preventDefault() {
            this.defaultPrevented = true;
        },
    };
}

function clickEvent(target, x, y) {
    return {
        target,
        clientX: x,
        clientY: y,
        defaultPrevented: false,
        preventDefault() {
            this.defaultPrevented = true;
        },
    };
}

function descendants(element) {
    const out = [];
    for (const child of element.children ?? []) {
        out.push(child);
        out.push(...descendants(child));
    }
    return out;
}

test('popup touch tap selects text even when WebView suppresses the follow-up click', () => {
    const { context, selectTextCalls, tapOutsideMessages } = popupContext();
    const container = new FakeContainer();
    const target = new FakeElement(['.glossary-content']);

    context.installPopupTapHandlers(container);
    container.dispatch('touchstart', touchEvent(target, 48, 148));
    const end = touchEvent(target, 48, 148, true);
    container.dispatch('touchend', end);

    assert.equal(selectTextCalls.length, 1);
    assert.deepEqual(selectTextCalls[0], [48, 148, 24, 48, 148]);
    assert.equal(tapOutsideMessages.length, 0);
    assert.equal(end.defaultPrevented, true);
});

test('popup touch tap suppresses the duplicate click generated for the same tap', () => {
    const { context, selectTextCalls } = popupContext();
    const container = new FakeContainer();
    const target = new FakeElement(['.glossary-content']);

    context.installPopupTapHandlers(container);
    container.dispatch('touchstart', touchEvent(target, 48, 148));
    container.dispatch('touchend', touchEvent(target, 48, 148, true));
    const duplicateClick = clickEvent(target, 49, 149);
    container.dispatch('click', duplicateClick);

    assert.equal(selectTextCalls.length, 1);
    assert.equal(duplicateClick.defaultPrevented, true);
});

test('popup touch tap lets interactive controls keep their click behavior', () => {
    const { context, selectTextCalls, tapOutsideMessages } = popupContext();
    const container = new FakeContainer();
    const target = new FakeElement(['summary']);

    context.installPopupTapHandlers(container);
    container.dispatch('touchstart', touchEvent(target, 48, 148));
    const end = touchEvent(target, 48, 148, true);
    container.dispatch('touchend', end);
    const click = clickEvent(target, 48, 148);
    container.dispatch('click', click);

    assert.equal(selectTextCalls.length, 0);
    assert.equal(tapOutsideMessages.length, 0);
    assert.equal(end.defaultPrevented, false);
    assert.equal(click.defaultPrevented, false);
});

test('popup click still selects text when there was no touch fallback', () => {
    const { context, selectTextCalls } = popupContext();
    const container = new FakeContainer();
    const target = new FakeElement(['.glossary-content']);

    context.installPopupTapHandlers(container);
    container.dispatch('click', clickEvent(target, 48, 148));

    assert.equal(selectTextCalls.length, 1);
});

test('popup content blank area click posts tapOutside through the document handler', () => {
    const { document, tapOutsideMessages } = popupContext();
    const target = new FakeElement();

    document.dispatch('click', clickEvent(target, 48, 480));

    assert.deepEqual(tapOutsideMessages, [null]);
});

test('popup viewport blank area click posts tapOutside when it misses body content', () => {
    const { document, tapOutsideMessages } = popupContext();

    document.dispatch('click', clickEvent(document.documentElement, 48, 640));

    assert.deepEqual(tapOutsideMessages, [null]);
});

test('popup action controls remain DOM buttons even if a legacy native button flag is present', () => {
    const { context } = popupContext();

    context.window.nativePopupButtons = true;
    const audioSlot = context.createButtonSlot('audio', 0);
    const mineSlot = context.createButtonSlot('mine', 1, false);

    assert.equal(audioSlot.tagName, 'BUTTON');
    assert.equal(audioSlot.type, 'button');
    assert.equal(audioSlot.getAttribute('aria-label'), 'Play audio');
    assert.equal(audioSlot.children.length, 1);
    assert.equal(audioSlot.children[0].className, 'button-slot-icon');
    assert.equal(mineSlot.tagName, 'BUTTON');
    assert.equal(mineSlot.disabled, true);
});

test('popup language detection works with split selection policy assets', () => {
    const { context } = popupContext({
        loadJapaneseLanguageAsset: true,
        loadSelectionAssets: true,
    });

    assert.doesNotThrow(() => context.getLanguageFromText('plain English glossary', 'en'));
    assert.equal(context.getLanguageFromText('plain English glossary', 'en'), 'en');
    assert.equal(context.getLanguageFromText('猫 glossary', 'en'), 'ja');
});

test('popup language detection does not depend on the selection object', () => {
    const { context } = popupContext({ loadJapaneseLanguageAsset: true });
    delete context.window.hoshiSelection;

    assert.equal(context.getLanguageFromText('猫 glossary', 'en'), 'ja');
});

test('popup transcription entries do not render as Japanese pitch accents', () => {
    const { context } = popupContext();

    const tags = context.createTags({
        expression: 'read',
        reading: 'read',
        deinflectionTrace: [],
        frequencies: [],
        pitches: [
            {
                dictionary: 'English',
                pitchPositions: [],
                transcriptions: ['/riːd/', '/rɛd/'],
            },
        ],
    });
    const nodes = descendants(tags);

    assert.ok(tags);
    assert.equal(nodes.some((node) => String(node.className).split(' ').includes('transcription-list')), true);
    assert.equal(nodes.some((node) => String(node.className).split(' ').includes('pitch-group')), false);
    assert.equal(nodes.some((node) => node.textContent === '/riːd/'), true);
});

test('popup preserves IPA dictionary transcription delimiters', () => {
    const { context } = popupContext();

    const tags = context.createTags({
        expression: 'read',
        reading: 'read',
        deinflectionTrace: [],
        frequencies: [],
        pitches: [
            {
                dictionary: 'seth-oald-ipa',
                pitchPositions: [],
                transcriptions: ['/riːd/'],
            },
        ],
    });
    const nodes = descendants(tags);

    assert.equal(nodes.some((node) => node.textContent === '/riːd/'), true);
    assert.equal(nodes.some((node) => node.textContent === '//riːd//'), false);
});

test('popup builds Yomitan-compatible phonetic transcriptions Anki HTML', () => {
    const { context } = popupContext();

    const html = context.constructPhoneticTranscriptionsHtml([
        {
            dictionary: 'seth-oald-ipa',
            pitchPositions: [],
            transcriptions: ['/riːd/', '/rɛd/'],
        },
    ]);

    assert.equal(
        html,
        '<ul><li class="pronunciation" data-pronunciation-type="phonetic-transcription">/riːd/</li><li class="pronunciation" data-pronunciation-type="phonetic-transcription">/rɛd/</li></ul>',
    );
});

test('mineEntry posts phonetic transcriptions for Anki handlebar rendering', async () => {
    const { context, mineEntryMessages } = popupContext();
    context.window.lookupEntries = [{ glossaries: [] }];

    await context.mineEntry(
        'read',
        'read',
        [],
        [{ dictionary: 'seth-oald-ipa', pitchPositions: [], transcriptions: ['/riːd/'] }],
        [],
        'read',
        0,
        'read',
    );

    assert.equal(mineEntryMessages.length, 1);
    assert.equal(
        mineEntryMessages[0].phoneticTranscriptions,
        '<ul><li class="pronunciation" data-pronunciation-type="phonetic-transcription">/riːd/</li></ul>',
    );
});
