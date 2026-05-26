(function() {
    const ORIGIN = 'https://hoshi.local';
    const LAYER_ID = 'hoshi-reader-popup-layer';
    const ACTION_BAR_HEIGHT = 37;
    const SASAYAKI_BAR_HEIGHT = 37;
    const frames = new Map();
    const frameSources = new WeakMap();
    let idleRootRecord = null;

    function ensureLayer() {
        let layer = document.getElementById(LAYER_ID);
        if (layer) return layer;
        layer = document.createElement('div');
        layer.id = LAYER_ID;
        layer.style.cssText = [
            'position:fixed',
            'inset:0',
            'z-index:2147483640',
            'pointer-events:none',
            'contain:layout style paint',
            'writing-mode:horizontal-tb',
            'direction:ltr',
            'text-orientation:mixed'
        ].join(';');
        document.documentElement.appendChild(layer);
        return layer;
    }

    function postNative(message) {
        if (!window.HoshiReaderPopup?.postMessage) return;
        window.HoshiReaderPopup.postMessage(JSON.stringify(message));
    }

    function icon(name) {
        return `https://hoshi.local/popup/icons/${name}.svg`;
    }

    function frameContentTop(payload) {
        return (payload.actionBarVisible ? ACTION_BAR_HEIGHT : 0) + (payload.sasayakiVisible ? SASAYAKI_BAR_HEIGHT : 0);
    }

    function button(iconName, enabled, action, label) {
        const item = document.createElement('button');
        item.type = 'button';
        item.className = 'hoshi-reader-popup-control';
        item.disabled = !enabled;
        item.setAttribute('aria-label', label);
        item.style.setProperty('--icon-url', `url("${icon(iconName)}")`);
        item.addEventListener('click', (event) => {
            event.preventDefault();
            event.stopPropagation();
            if (!item.disabled) action();
        });
        return item;
    }

    function buildBar(payload, className, buttons) {
        const bar = document.createElement('div');
        bar.className = className;
        buttons.forEach(item => bar.appendChild(item));
        return bar;
    }

    function renderControls(shell, payload, iframe) {
        shell.querySelectorAll('.hoshi-reader-popup-bar').forEach(node => node.remove());
        if (payload.actionBarVisible) {
            shell.insertBefore(
                buildBar(payload, 'hoshi-reader-popup-bar hoshi-reader-popup-action-bar', [
                    button('arrow_back', payload.backCount > 0, () => {
                        iframe.contentWindow?.postMessage({ type: 'navigateBack' }, ORIGIN);
                        postNative({ name: 'navigateBack', popupId: payload.id });
                    }, 'Back'),
                    button('arrow_forward', payload.forwardCount > 0, () => {
                        iframe.contentWindow?.postMessage({ type: 'navigateForward' }, ORIGIN);
                        postNative({ name: 'navigateForward', popupId: payload.id });
                    }, 'Forward'),
                    button('close', true, () => postNative({ name: 'swipeDismiss', popupId: payload.id }), 'Close')
                ]),
                iframe,
            );
        }
        if (payload.sasayakiVisible) {
            shell.insertBefore(
                buildBar(payload, 'hoshi-reader-popup-bar hoshi-reader-popup-sasayaki-bar', [
                    button('replay', true, () => postNative({ name: 'sasayakiReplayCue', popupId: payload.id }), 'Replay cue'),
                    button((payload.sasayakiIsPlaying || payload.sasayakiWasPaused) ? 'pause' : 'play_arrow', true, () => postNative({ name: 'sasayakiTogglePlayback', popupId: payload.id }), 'Play or pause'),
                    button('start', true, () => postNative({ name: 'sasayakiPlayForward', popupId: payload.id }), 'Play from cue')
                ]),
                iframe,
            );
        }
    }

    function applyShellStyle(shell, payload) {
        const frame = payload.frame;
        shell.style.left = `${frame.left}px`;
        shell.style.top = `${frame.top}px`;
        shell.style.width = `${frame.width}px`;
        shell.style.height = `${frame.height}px`;
        shell.dataset.popupId = payload.id;
        shell.dataset.darkMode = String(!!payload.darkMode);
        shell.dataset.eInkMode = String(!!payload.eInkMode);
    }

    function iframeRenderMessage(payload) {
        return {
            type: 'renderPopup',
            popupId: payload.id,
            entriesCount: payload.entriesCount || 0,
            initialEntryJson: payload.initialEntryJson || null
        };
    }

    function renderIframe(record) {
        record.iframe.contentWindow?.postMessage(iframeRenderMessage(record.payload), ORIGIN);
    }

    function setContentReady(record, ready) {
        record.contentReady = ready;
        record.shell.dataset.contentReady = String(ready);
    }

    function resetIframe(record) {
        record.iframe.contentWindow?.postMessage({ type: 'resetPopup' }, ORIGIN);
    }

    function createRecord(payload = null) {
        const shell = document.createElement('div');
        shell.className = 'hoshi-reader-popup-shell';
        shell.dataset.contentReady = 'false';
        const iframe = document.createElement('iframe');
        iframe.className = 'hoshi-reader-popup-iframe';
        iframe.setAttribute('sandbox', 'allow-scripts allow-same-origin');
        const record = { shell, iframe, payload, contentReady: false, loaded: false, root: false };
        iframe.addEventListener('load', () => {
            record.loaded = true;
            if (record.payload) {
                frameSources.set(iframe.contentWindow, record.payload.id);
                renderIframe(record);
            }
        });
        shell.appendChild(iframe);
        return record;
    }

    function preloadRoot(iframeUrl) {
        window.__hoshiReaderPopupIframeUrl = iframeUrl;
        if (!iframeUrl || frames.size > 0) return;
        const layer = ensureLayer();
        if (!idleRootRecord) {
            idleRootRecord = createRecord();
            layer.appendChild(idleRootRecord.shell);
        }
        setContentReady(idleRootRecord, false);
        resetIframe(idleRootRecord);
        idleRootRecord.payload = null;
        idleRootRecord.root = false;
        if (idleRootRecord.iframe.src !== iframeUrl) {
            idleRootRecord.loaded = false;
            idleRootRecord.iframe.src = iframeUrl;
        }
    }

    function activateRecord(payload, isRoot) {
        let record = frames.get(payload.id);
        let needsRender = false;
        if (!record) {
            if (isRoot && idleRootRecord) {
                record = idleRootRecord;
                idleRootRecord = null;
                needsRender = true;
            } else {
                record = createRecord(payload);
                ensureLayer().appendChild(record.shell);
                needsRender = true;
            }
            frames.set(payload.id, record);
        }
        if (record.payload?.id !== payload.id) {
            needsRender = true;
        }
        record.root = isRoot;
        record.payload = payload;
        if (needsRender) {
            setContentReady(record, false);
            resetIframe(record);
            if (record.iframe.contentWindow) {
                frameSources.set(record.iframe.contentWindow, payload.id);
            }
        }
        return { record, needsRender };
    }

    function renderPayload(payload, index) {
        const isRoot = index === 0;
        const { record, needsRender } = activateRecord(payload, isRoot);

        if (record.clearSelectionSignal !== undefined && record.clearSelectionSignal !== payload.clearSelectionSignal) {
            record.iframe.contentWindow?.postMessage({ type: 'clearSelection' }, ORIGIN);
        }
        record.clearSelectionSignal = payload.clearSelectionSignal;
        applyShellStyle(record.shell, payload);
        renderControls(record.shell, payload, record.iframe);
        record.iframe.style.top = `${frameContentTop(payload)}px`;
        record.iframe.style.height = `calc(100% - ${frameContentTop(payload)}px)`;
        if (record.iframe.src !== payload.iframeUrl) {
            record.loaded = false;
            record.iframe.src = payload.iframeUrl;
        } else if (needsRender && record.loaded) {
            renderIframe(record);
        }
    }

    function parkRootRecord(record) {
        setContentReady(record, false);
        resetIframe(record);
        record.payload = null;
        record.clearSelectionSignal = undefined;
        record.root = false;
        record.shell.dataset.popupId = '';
        record.shell.querySelectorAll('.hoshi-reader-popup-bar').forEach(node => node.remove());
        record.iframe.style.top = '0px';
        record.iframe.style.height = '100%';
        idleRootRecord = record;
    }

    function removeMissing(activeIds) {
        for (const [id, record] of frames.entries()) {
            if (activeIds.has(id)) continue;
            frames.delete(id);
            if (record.root && !idleRootRecord) {
                parkRootRecord(record);
            } else {
                resetIframe(record);
                record.shell.remove();
            }
        }
        const layer = document.getElementById(LAYER_ID);
        if (layer && frames.size === 0 && !idleRootRecord) {
            layer.remove();
        }
    }

    function renderStack(payload) {
        const items = Array.isArray(payload) ? payload : (payload?.popups || []);
        const activeIds = new Set(items.map(item => item.id));
        items.forEach((item, index) => renderPayload(item, index));
        removeMissing(activeIds);
    }

    function resolveMessage(popupId, id, body) {
        const record = frames.get(popupId);
        record?.iframe.contentWindow?.postMessage({ type: 'reply', id, body }, ORIGIN);
    }

    function highlightSelection(popupId, count) {
        const record = frames.get(popupId);
        record?.iframe.contentWindow?.postMessage({ type: 'highlightSelection', count }, ORIGIN);
    }

    function adjustSelectionBody(popupId, body) {
        const record = frames.get(popupId);
        if (!record || !body?.rect) return body;
        return {
            ...body,
            rect: {
                ...body.rect,
                x: body.rect.x + record.payload.frame.left,
                y: body.rect.y + record.payload.frame.top + frameContentTop(record.payload)
            }
        };
    }

    window.addEventListener('message', function(event) {
        if (event.origin !== ORIGIN) return;
        const data = event.data || {};
        const popupId = data.popupId || frameSources.get(event.source);
        if (data.source !== 'hoshi-popup-iframe' || !popupId) return;
        const record = frames.get(popupId);
        if (data.name === 'contentReady' && record) {
            setContentReady(record, true);
        }
        const body = data.name === 'textSelected' ? adjustSelectionBody(popupId, data.body) : data.body;
        postNative({
            name: data.name,
            id: data.id || null,
            popupId,
            body: body === undefined ? null : body
        });
    });

    const style = document.createElement('style');
    style.textContent = `
        #${LAYER_ID} .hoshi-reader-popup-shell {
            position: fixed;
            box-sizing: border-box;
            overflow: hidden;
            pointer-events: auto;
            writing-mode: horizontal-tb;
            direction: ltr;
            text-orientation: mixed;
            background: #fff;
            border: 1px solid rgba(120, 120, 128, 0.36);
            border-radius: 10px;
            box-shadow: 0 3px 12px rgba(0, 0, 0, 0.22);
            visibility: hidden;
            opacity: 0;
            pointer-events: none;
        }
        #${LAYER_ID} .hoshi-reader-popup-shell[data-content-ready="true"] {
            visibility: visible;
            opacity: 1;
            pointer-events: auto;
        }
        #${LAYER_ID} .hoshi-reader-popup-shell[data-dark-mode="true"] {
            background: #000;
            border-color: rgba(255, 255, 255, 0.34);
            box-shadow: 0 3px 12px rgba(0, 0, 0, 0.44);
        }
        #${LAYER_ID} .hoshi-reader-popup-shell[data-e-ink-mode="true"] {
            border-radius: 0;
            box-shadow: none;
            border-color: #000;
        }
        #${LAYER_ID} .hoshi-reader-popup-shell[data-dark-mode="true"][data-e-ink-mode="true"] {
            border-color: #fff;
        }
        #${LAYER_ID} .hoshi-reader-popup-iframe {
            position: absolute;
            box-sizing: border-box;
            left: 0;
            width: 100%;
            border: 0;
            background: transparent;
        }
        #${LAYER_ID} .hoshi-reader-popup-bar {
            box-sizing: border-box;
            width: 100%;
            writing-mode: horizontal-tb;
            direction: ltr;
            height: ${ACTION_BAR_HEIGHT}px;
            display: flex;
            align-items: center;
            padding: 0 4px;
            border-bottom: 1px solid rgba(120, 120, 128, 0.36);
            color: rgba(60, 60, 67, 0.86);
            background: inherit;
        }
        #${LAYER_ID} .hoshi-reader-popup-sasayaki-bar {
            justify-content: center;
            gap: 18px;
        }
        #${LAYER_ID} .hoshi-reader-popup-action-bar {
            justify-content: space-between;
        }
        #${LAYER_ID} .hoshi-reader-popup-shell[data-dark-mode="true"] .hoshi-reader-popup-bar {
            color: rgba(255, 255, 255, 0.92);
            border-bottom-color: rgba(255, 255, 255, 0.34);
        }
        #${LAYER_ID} .hoshi-reader-popup-control {
            appearance: none;
            width: 34px;
            height: 34px;
            border: 0;
            border-radius: 17px;
            background: transparent;
            color: inherit;
            padding: 7px;
            -webkit-tap-highlight-color: transparent;
        }
        #${LAYER_ID} .hoshi-reader-popup-control:active {
            background: rgba(128, 128, 128, 0.18);
        }
        #${LAYER_ID} .hoshi-reader-popup-control:disabled {
            opacity: 0.34;
        }
        #${LAYER_ID} .hoshi-reader-popup-control::before {
            content: "";
            display: block;
            width: 100%;
            height: 100%;
            background: currentColor;
            -webkit-mask-image: var(--icon-url);
            mask-image: var(--icon-url);
            -webkit-mask-position: center;
            mask-position: center;
            -webkit-mask-repeat: no-repeat;
            mask-repeat: no-repeat;
            -webkit-mask-size: contain;
            mask-size: contain;
        }
    `;
    document.documentElement.appendChild(style);

    window.hoshiReaderPopupHost = {
        renderStack,
        resolveMessage,
        highlightSelection,
        preloadRoot
    };
    if (window.__hoshiReaderPopupIframeUrl) {
        preloadRoot(window.__hoshiReaderPopupIframeUrl);
    }
    if (window.__hoshiPendingReaderPopupStack) {
        renderStack(window.__hoshiPendingReaderPopupStack);
        window.__hoshiPendingReaderPopupStack = null;
    }
})();
