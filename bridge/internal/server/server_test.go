package server

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/steindekker/hoshi-reader/bridge/internal/hook"
	"github.com/steindekker/hoshi-reader/bridge/internal/hub"
	"github.com/steindekker/hoshi-reader/bridge/internal/screenshot"
	"github.com/steindekker/hoshi-reader/bridge/internal/status"
	"github.com/steindekker/hoshi-reader/bridge/internal/store"
)

type fakeCapturer struct {
	mu    sync.Mutex
	img   image.Image
	err   error
	calls int
}

func (f *fakeCapturer) Capture(ctx context.Context) (screenshot.Shot, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	if f.err != nil {
		return screenshot.Shot{}, f.err
	}
	return screenshot.Shot{Image: f.img, CapturedAt: time.UnixMilli(1759600000123), Backend: "fake"}, nil
}

type env struct {
	srv     *Server
	ts      *httptest.Server
	store   *store.Store
	hub     *hub.Hub
	tracker *status.Tracker
	cache   *screenshot.Cache
	shots   *fakeCapturer
}

func solid(w, h int, c color.Color) image.Image {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, c)
		}
	}
	return img
}

func newEnv(t *testing.T, token string, withShots bool) *env {
	t.Helper()
	h := hub.New()
	st, err := store.Open(store.Options{MaxLines: 5000, Dedupe: true, OnAdd: func(l store.Line) { h.Publish(l) }})
	if err != nil {
		t.Fatal(err)
	}
	tr := status.NewTracker()
	tr.SetOnChange(func() { h.Publish(StatusChanged{}) })
	tr.Register("http", "http", "POST /api/lines", true)
	e := &env{store: st, hub: h, tracker: tr, cache: screenshot.NewCache(10)}
	opts := Options{
		Version: "test", Hostname: "deck", Token: token,
		Store: st, Hub: h, Tracker: tr, Cache: e.cache,
		PingInterval: 200 * time.Millisecond,
	}
	if withShots {
		e.shots = &fakeCapturer{img: solid(1600, 1000, color.RGBA{200, 30, 30, 255})}
		opts.Shots = e.shots
	}
	e.srv = New(opts)
	e.ts = httptest.NewServer(e.srv.Handler())
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		e.srv.Shutdown(ctx)
		e.ts.Close()
	})
	return e
}

func (e *env) do(t *testing.T, method, path, ctype, body, token string) (*http.Response, []byte) {
	t.Helper()
	req, _ := http.NewRequest(method, e.ts.URL+path, strings.NewReader(body))
	if ctype != "" {
		req.Header.Set("Content-Type", ctype)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp, b
}

func decode[T any](t *testing.T, b []byte) T {
	t.Helper()
	var v T
	if err := json.Unmarshal(b, &v); err != nil {
		t.Fatalf("decode %s: %v", b, err)
	}
	return v
}

func TestStatus(t *testing.T) {
	e := newEnv(t, "", true)
	e.tracker.Register("textractor", "websocket", "ws://127.0.0.1:6677", false).Set(false, errors.New("connection refused"))
	e.tracker.SetScreenshot(true, "gamescope")
	e.store.Add("一", "textractor")

	resp, b := e.do(t, "GET", "/api/status", "", "", "")
	if resp.StatusCode != 200 {
		t.Fatalf("status %d", resp.StatusCode)
	}
	var raw map[string]any
	json.Unmarshal(b, &raw)
	for _, k := range []string{"server", "version", "protocol", "hostname", "latestLineId", "sources", "screenshot", "hook"} {
		if _, ok := raw[k]; !ok {
			t.Errorf("status lacks %q: %s", k, b)
		}
	}
	st := decode[Status](t, b)
	if st.Server != "steamdeck-vn-extractor" || st.Protocol != 1 || st.LatestLineID != 1 || st.Hostname != "deck" {
		t.Fatalf("bad status %+v", st)
	}
	if len(st.Sources) != 2 || st.Sources[0].Kind != "http" || st.Sources[1].ID != "textractor" ||
		st.Sources[1].LastError == nil || *st.Sources[1].LastError != "connection refused" {
		t.Fatalf("bad sources %+v", st.Sources)
	}
	if !st.Screenshot.Available || st.Screenshot.Backend == nil || *st.Screenshot.Backend != "gamescope" {
		t.Fatalf("bad screenshot %+v", st.Screenshot)
	}
	if st.Hook.Enabled || st.Hook.State != hook.StateIdle || st.Hook.Mode != hook.ModeAuto || st.Hook.Game != nil || st.Hook.PID != nil {
		t.Fatalf("hook should be idle when not configured: %+v", st.Hook)
	}
	// lastError must serialize as null when absent.
	if !strings.Contains(string(b), `"lastError":null`) {
		t.Fatalf("http source should have lastError null: %s", b)
	}
}

func TestStatusEmptyStore(t *testing.T) {
	e := newEnv(t, "", false)
	_, b := e.do(t, "GET", "/api/status", "", "", "")
	st := decode[Status](t, b)
	if st.LatestLineID != 0 || st.Screenshot.Available || st.Screenshot.Backend != nil {
		t.Fatalf("bad empty status %+v", st)
	}
	if !strings.Contains(string(b), `"backend":null`) {
		t.Fatalf("backend should be null: %s", b)
	}
}

type linesResp struct {
	Lines        []store.Line `json:"lines"`
	LatestLineID int64        `json:"latestLineId"`
}

func TestGetLinesPagination(t *testing.T) {
	e := newEnv(t, "", false)
	_, b := e.do(t, "GET", "/api/lines", "", "", "")
	if r := decode[linesResp](t, b); len(r.Lines) != 0 || r.LatestLineID != 0 || !strings.Contains(string(b), `"lines":[]`) {
		t.Fatalf("empty store: %s", b)
	}
	for i := 1; i <= 250; i++ {
		e.store.Add(fmt.Sprintf("line %d", i), "t")
	}
	_, b = e.do(t, "GET", "/api/lines", "", "", "")
	r := decode[linesResp](t, b)
	if len(r.Lines) != 100 || r.Lines[0].ID != 151 || r.Lines[99].ID != 250 || r.LatestLineID != 250 {
		t.Fatalf("default: %d lines, first %d", len(r.Lines), r.Lines[0].ID)
	}
	_, b = e.do(t, "GET", "/api/lines?after=10&limit=5", "", "", "")
	r = decode[linesResp](t, b)
	if len(r.Lines) != 5 || r.Lines[0].ID != 11 || r.Lines[4].ID != 15 {
		t.Fatalf("after=10&limit=5: %+v", r.Lines)
	}
	_, b = e.do(t, "GET", "/api/lines?limit=5000", "", "", "")
	if r = decode[linesResp](t, b); len(r.Lines) != 250 {
		t.Fatalf("limit clamp: %d", len(r.Lines))
	}
	_, b = e.do(t, "GET", "/api/lines?after=250", "", "", "")
	if r = decode[linesResp](t, b); len(r.Lines) != 0 {
		t.Fatalf("after latest: %d", len(r.Lines))
	}
	for _, q := range []string{"limit=0", "limit=x", "after=-1", "after=abc"} {
		resp, b := e.do(t, "GET", "/api/lines?"+q, "", "", "")
		if resp.StatusCode != 400 || !strings.Contains(string(b), `"error"`) {
			t.Errorf("%s: %d %s", q, resp.StatusCode, b)
		}
	}
}

func TestPostLines(t *testing.T) {
	e := newEnv(t, "", false)
	resp, b := e.do(t, "POST", "/api/lines", "text/plain; charset=utf-8", "  「こんにちは」 \n", "")
	if resp.StatusCode != 201 {
		t.Fatalf("plain: %d %s", resp.StatusCode, b)
	}
	l := decode[store.Line](t, b)
	if l.ID != 1 || l.Text != "「こんにちは」" || l.Source != "http" || l.ReceivedAt == 0 || l.HasScreenshot {
		t.Fatalf("bad line %+v", l)
	}
	resp, b = e.do(t, "POST", "/api/lines", "text/plain", "「こんにちは」", "")
	if resp.StatusCode != 200 || strings.TrimSpace(string(b)) != `{"duplicate":true}` {
		t.Fatalf("duplicate: %d %s", resp.StatusCode, b)
	}
	resp, b = e.do(t, "POST", "/api/lines", "application/json", `{"text":"次","source":"myhook"}`, "")
	if resp.StatusCode != 201 {
		t.Fatalf("json: %d %s", resp.StatusCode, b)
	}
	if l := decode[store.Line](t, b); l.Source != "myhook" || l.ID != 2 {
		t.Fatalf("json line %+v", l)
	}
	resp, _ = e.do(t, "POST", "/api/lines", "application/json", `{"text":"その次"}`, "")
	if resp.StatusCode != 201 {
		t.Fatalf("json without source: %d", resp.StatusCode)
	}
	for _, c := range []struct{ ctype, body string }{
		{"text/plain", ""}, {"text/plain", "  \n "}, {"application/json", `{"text":""}`}, {"application/json", `{bad`},
	} {
		resp, b := e.do(t, "POST", "/api/lines", c.ctype, c.body, "")
		if resp.StatusCode != 400 || !strings.Contains(string(b), `"error"`) {
			t.Errorf("%q: %d %s", c.body, resp.StatusCode, b)
		}
	}
}

func TestAuth(t *testing.T) {
	e := newEnv(t, "s3cret", false)
	for _, p := range []string{"/api/status", "/api/lines"} {
		if resp, b := e.do(t, "GET", p, "", "", ""); resp.StatusCode != 401 || !strings.Contains(string(b), `"error"`) {
			t.Errorf("%s without token: %d", p, resp.StatusCode)
		}
		if resp, _ := e.do(t, "GET", p, "", "", "wrong"); resp.StatusCode != 401 {
			t.Errorf("%s wrong token: %d", p, resp.StatusCode)
		}
		if resp, _ := e.do(t, "GET", p, "", "", "s3cret"); resp.StatusCode != 200 {
			t.Errorf("%s good token: %d", p, resp.StatusCode)
		}
	}
	if resp, _ := e.do(t, "GET", "/api/status?token=s3cret", "", "", ""); resp.StatusCode != 401 {
		t.Errorf("query token must only work for /api/ws, got %d", resp.StatusCode)
	}
	if resp, _ := e.do(t, "POST", "/api/screenshot", "", "", ""); resp.StatusCode != 401 {
		t.Errorf("screenshot without token: %d", resp.StatusCode)
	}
	// Debug page is not under /api and stays reachable.
	if resp, b := e.do(t, "GET", "/", "", "", ""); resp.StatusCode != 200 || !bytes.Contains(b, []byte("<html")) {
		t.Errorf("debug page: %d", resp.StatusCode)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	wsURL := "ws" + strings.TrimPrefix(e.ts.URL, "http") + "/api/ws"
	if _, resp, err := websocket.Dial(ctx, wsURL, nil); err == nil || resp == nil || resp.StatusCode != 401 {
		t.Fatalf("ws without token should be 401, err=%v", err)
	}
	c, _, err := websocket.Dial(ctx, wsURL+"?token=s3cret", nil)
	if err != nil {
		t.Fatalf("ws with query token: %v", err)
	}
	c.CloseNow()
	hdr := http.Header{"Authorization": {"Bearer s3cret"}}
	c, _, err = websocket.Dial(ctx, wsURL, &websocket.DialOptions{HTTPHeader: hdr})
	if err != nil {
		t.Fatalf("ws with header token: %v", err)
	}
	c.CloseNow()
}

func TestScreenshotCapture(t *testing.T) {
	e := newEnv(t, "", true)
	resp, b := e.do(t, "POST", "/api/screenshot", "", "", "")
	if resp.StatusCode != 200 || resp.Header.Get("Content-Type") != "image/jpeg" {
		t.Fatalf("status %d type %s: %s", resp.StatusCode, resp.Header.Get("Content-Type"), b)
	}
	if resp.Header.Get("X-Screenshot-Captured-At") != "1759600000123" {
		t.Fatalf("captured-at header = %q", resp.Header.Get("X-Screenshot-Captured-At"))
	}
	if resp.Header.Get("X-Screenshot-Line-Id") != "" {
		t.Fatal("fresh capture must not carry X-Screenshot-Line-Id")
	}
	img, err := jpeg.Decode(bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	if img.Bounds().Dx() != 1280 || img.Bounds().Dy() != 800 {
		t.Fatalf("default maxWidth: got %v", img.Bounds())
	}
	_, b = e.do(t, "POST", "/api/screenshot?maxWidth=0&quality=50", "", "", "")
	if img, _ = jpeg.Decode(bytes.NewReader(b)); img.Bounds().Dx() != 1600 {
		t.Fatalf("maxWidth=0: %v", img.Bounds())
	}
	_, b = e.do(t, "POST", "/api/screenshot?maxWidth=4000", "", "", "")
	if img, _ = jpeg.Decode(bytes.NewReader(b)); img.Bounds().Dx() != 1600 {
		t.Fatalf("must never upscale: %v", img.Bounds())
	}
	_, b = e.do(t, "POST", "/api/screenshot?maxWidth=400", "", "", "")
	if img, _ = jpeg.Decode(bytes.NewReader(b)); img.Bounds().Dx() != 400 || img.Bounds().Dy() != 250 {
		t.Fatalf("maxWidth=400: %v", img.Bounds())
	}
	for _, q := range []string{"quality=0", "quality=101", "maxWidth=-1", "lineId=x"} {
		if resp, _ := e.do(t, "POST", "/api/screenshot?"+q, "", "", ""); resp.StatusCode != 400 {
			t.Errorf("%s: %d", q, resp.StatusCode)
		}
	}
}

func TestScreenshotCachedPerLine(t *testing.T) {
	e := newEnv(t, "", true)
	l, _, _ := e.store.Add("台詞", "t")
	var buf bytes.Buffer
	jpeg.Encode(&buf, solid(640, 400, color.RGBA{0, 0, 255, 255}), &jpeg.Options{Quality: 95})
	e.cache.Put(screenshot.Cached{LineID: l.ID, JPEG: buf.Bytes(), CapturedAt: time.UnixMilli(42)})

	_, b := e.do(t, "GET", "/api/lines", "", "", "")
	if r := decode[linesResp](t, b); !r.Lines[0].HasScreenshot {
		t.Fatal("hasScreenshot should be true for cached line")
	}
	resp, b := e.do(t, "POST", "/api/screenshot?lineId="+strconv.FormatInt(l.ID, 10), "", "", "")
	if resp.StatusCode != 200 || resp.Header.Get("X-Screenshot-Line-Id") != "1" || resp.Header.Get("X-Screenshot-Captured-At") != "42" {
		t.Fatalf("cached: %d %v", resp.StatusCode, resp.Header)
	}
	img, _ := jpeg.Decode(bytes.NewReader(b))
	if r, _, bl, _ := img.At(10, 10).RGBA(); bl < 0xc000 || r > 0x4000 {
		t.Fatal("expected the cached (blue) image")
	}
	if e.shots.calls != 0 {
		t.Fatal("cached screenshot must not trigger a capture")
	}
	// Unknown line id falls back to capturing now.
	resp, _ = e.do(t, "POST", "/api/screenshot?lineId=99", "", "", "")
	if resp.StatusCode != 200 || resp.Header.Get("X-Screenshot-Line-Id") != "" || e.shots.calls != 1 {
		t.Fatalf("fallback capture: %d calls=%d", resp.StatusCode, e.shots.calls)
	}
}

func TestScreenshotUnavailable(t *testing.T) {
	e := newEnv(t, "", false)
	resp, b := e.do(t, "POST", "/api/screenshot", "", "", "")
	if resp.StatusCode != 503 || !strings.Contains(string(b), `"error"`) {
		t.Fatalf("disabled: %d %s", resp.StatusCode, b)
	}
	e2 := newEnv(t, "", true)
	e2.shots.err = fmt.Errorf("%w (gamescope: no socket)", screenshot.ErrUnavailable)
	resp, b = e2.do(t, "POST", "/api/screenshot", "", "", "")
	if resp.StatusCode != 503 || !strings.Contains(string(b), "no socket") {
		t.Fatalf("failing backend: %d %s", resp.StatusCode, b)
	}
}

type fakeHooks struct {
	snap      hook.Snapshot
	nilSelect bool
}

func (f *fakeHooks) View() hook.PublicStatus {
	game := "sgre_steam.exe"
	pid := 20
	return hook.PublicStatus{Enabled: true, State: hook.StateAttached, Game: &game, PID: &pid, Mode: f.snap.Mode}
}

func (f *fakeHooks) Threads() hook.Snapshot { return f.snap }

func (f *fakeHooks) Select(id *string) error {
	if id == nil {
		f.nilSelect = true
		f.snap.Mode = hook.ModeAuto
		f.snap.SelectedID = ""
		return nil
	}
	if *id != "t1" {
		return hook.ErrUnknownThread
	}
	f.snap.Mode = hook.ModeManual
	f.snap.SelectedID = *id
	return nil
}

func TestHookThreadsAPI(t *testing.T) {
	e := newEnv(t, "secret", false)
	resp, b := e.do(t, "GET", "/api/hook/threads", "", "", "")
	if resp.StatusCode != 401 {
		t.Fatalf("auth %d %s", resp.StatusCode, b)
	}
	resp, b = e.do(t, "POST", "/api/hook/threads/select", "application/json", `{"threadId":null}`, "secret")
	if resp.StatusCode != 409 {
		t.Fatalf("disabled select %d %s", resp.StatusCode, b)
	}

	f := &fakeHooks{snap: hook.Snapshot{
		Mode: hook.ModeAuto,
		Threads: []hook.ThreadInfo{{
			ID: "t1", Name: "render", HookCode: "HS@0:game.exe", Score: 0.9,
			Samples: []hook.Sample{{Text: "「こんにちは」", At: 10}},
		}},
	}}
	e.srv.opts.Hooks = f
	resp, b = e.do(t, "GET", "/api/hook/threads", "", "", "secret")
	if resp.StatusCode != 200 || !strings.Contains(string(b), `"selectedThreadId":null`) || !strings.Contains(string(b), "「こんにちは」") {
		t.Fatalf("threads %d %s", resp.StatusCode, b)
	}
	resp, b = e.do(t, "POST", "/api/hook/threads/select", "application/json", `{"threadId":"missing"}`, "secret")
	if resp.StatusCode != 404 {
		t.Fatalf("unknown %d %s", resp.StatusCode, b)
	}
	resp, b = e.do(t, "POST", "/api/hook/threads/select", "application/json", `{"threadId":"t1"}`, "secret")
	if resp.StatusCode != 200 || f.snap.SelectedID != "t1" || !strings.Contains(string(b), `"mode":"manual"`) {
		t.Fatalf("select %d %s snap=%+v", resp.StatusCode, b, f.snap)
	}
	resp, b = e.do(t, "POST", "/api/hook/threads/select", "application/json", `{"threadId":null}`, "secret")
	if resp.StatusCode != 200 || !f.nilSelect || f.snap.Mode != hook.ModeAuto {
		t.Fatalf("back to auto %d %s", resp.StatusCode, b)
	}
	resp, b = e.do(t, "POST", "/api/hook/threads/select", "application/json", `{"nope":1}`, "secret")
	if resp.StatusCode != 400 {
		t.Fatalf("bad body %d %s", resp.StatusCode, b)
	}

	c, ctx := dialWS(t, e, "?token=secret")
	hello := readMsg(t, ctx, c)
	if hello.Type != "hello" || hello.Status == nil || !hello.Status.Hook.Enabled || hello.Status.Hook.State != hook.StateAttached {
		t.Fatalf("hello hook %+v", hello.Status)
	}
	e.hub.Publish(ThreadsChanged{})
	typ, body, err := c.Read(ctx)
	if err != nil || typ != websocket.MessageText || !strings.Contains(string(body), `"type":"threads"`) {
		t.Fatalf("threads frame %s err=%v", body, err)
	}
}

func TestUnknownAPIPath(t *testing.T) {
	e := newEnv(t, "", false)
	resp, b := e.do(t, "GET", "/api/nope", "", "", "")
	if resp.StatusCode != 404 || !strings.Contains(string(b), `"error"`) {
		t.Fatalf("%d %s", resp.StatusCode, b)
	}
}
