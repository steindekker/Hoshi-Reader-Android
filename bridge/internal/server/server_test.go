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
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/steindekker/hoshi-reader/bridge/internal/hook"
	"github.com/steindekker/hoshi-reader/bridge/internal/hub"
	"github.com/steindekker/hoshi-reader/bridge/internal/screenshot"
	"github.com/steindekker/hoshi-reader/bridge/internal/session"
	"github.com/steindekker/hoshi-reader/bridge/internal/status"
	"github.com/steindekker/hoshi-reader/bridge/internal/store"
	"github.com/steindekker/hoshi-reader/bridge/internal/vn"
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

// fakeVideo is a video session handler recording its commands.
type fakeVideo struct {
	mu       sync.Mutex
	controls []string
	shots    []session.ScreenshotRequest
	ranges   []session.RangeRequest
	err      error
}

func (f *fakeVideo) Control(ctx context.Context, action string, ms *int64) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	s := action
	if ms != nil {
		s = fmt.Sprintf("%s %d", action, *ms)
	}
	f.controls = append(f.controls, s)
	return f.err
}

func (f *fakeVideo) Screenshot(ctx context.Context, req session.ScreenshotRequest) (session.Media, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.shots = append(f.shots, req)
	return session.Media{Data: []byte("jpeg"), ContentType: "image/jpeg"}, f.err
}

func (f *fakeVideo) Audio(ctx context.Context, req session.RangeRequest) (session.Media, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.ranges = append(f.ranges, req)
	return session.Media{Data: []byte("mp3"), ContentType: "audio/mpeg"}, f.err
}

func (f *fakeVideo) Clip(ctx context.Context, req session.RangeRequest) (session.Media, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.ranges = append(f.ranges, req)
	return session.Media{Data: []byte("webp"), ContentType: "image/webp"}, f.err
}

type env struct {
	srv     *Server
	ts      *httptest.Server
	store   *store.Store
	hub     *hub.Hub
	reg     *session.Registry
	vn      *vn.Session
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
	reg := session.NewRegistry(h)
	e := &env{hub: h, reg: reg, cache: screenshot.NewCache(10)}
	var capturer vn.Capturer
	if withShots {
		e.shots = &fakeCapturer{img: solid(1600, 1000, color.RGBA{200, 30, 30, 255})}
		capturer = e.shots
	}
	e.vn = vn.New(reg, capturer, e.cache)
	st, err := store.Open(store.Options{MaxLines: 5000, Dedupe: true, OnAdd: e.vn.Add})
	if err != nil {
		t.Fatal(err)
	}
	e.store = st
	tr := status.NewTracker()
	tr.SetOnChange(func() { h.Publish(StatusChanged{}) })
	tr.Register("http", "http", "POST /v2/ingest/lines", true)
	e.tracker = tr
	e.srv = New(Options{
		Version: "test", Hostname: "deck", Token: token,
		Registry: reg, Lines: st, Tracker: tr,
		PingInterval: 200 * time.Millisecond,
		ReservedIDs:  []string{vn.SessionID, "mpv"},
	})
	e.ts = httptest.NewServer(e.srv.Handler())
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		e.srv.Shutdown(ctx)
		e.ts.Close()
	})
	return e
}

func (e *env) addVideo(h session.Handler) {
	e.reg.Upsert(session.Info{
		ID: "mpv", Kind: session.KindVideo, Title: "Frieren 01", Source: "mpv",
		Capabilities: []string{session.CapControl, session.CapScreenshot, session.CapAudio, session.CapClip},
		TrackID:      "t1",
	}, h, 0)
	e.reg.ReplaceCues("mpv", "t1", []session.Cue{
		{ID: 1, Text: "一", StartMs: session.Ms(1000), EndMs: session.Ms(3000)},
		{ID: 2, Text: "二", StartMs: session.Ms(4000), EndMs: session.Ms(20000)},
	})
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

type statusResp struct {
	Bridge   Bridge         `json:"bridge"`
	Sessions []session.Info `json:"sessions"`
}

func TestStatus(t *testing.T) {
	e := newEnv(t, "", true)
	e.tracker.Register("textractor", "websocket", "ws://127.0.0.1:6677", false).Set(false, errors.New("connection refused"))
	e.tracker.SetScreenshot(true, "gamescope")
	e.store.Add("一", "textractor")

	resp, b := e.do(t, "GET", "/v2/status", "", "", "")
	if resp.StatusCode != 200 {
		t.Fatalf("status %d", resp.StatusCode)
	}
	st := decode[statusResp](t, b)
	if st.Bridge.Name != "hoshi-bridge" || st.Bridge.Protocol != 2 || st.Bridge.Hostname != "deck" {
		t.Fatalf("bad bridge %+v", st.Bridge)
	}
	if len(st.Bridge.Sources) != 2 || st.Bridge.Sources[1].LastError == nil || *st.Bridge.Sources[1].LastError != "connection refused" {
		t.Fatalf("bad sources %+v", st.Bridge.Sources)
	}
	if !st.Bridge.Screenshot.Available || st.Bridge.Hook.State != hook.StateIdle {
		t.Fatalf("bad screenshot/hook %+v", st.Bridge)
	}
	if len(st.Sessions) != 1 || st.Sessions[0].ID != "vn" || st.Sessions[0].Kind != session.KindVN ||
		!st.Sessions[0].Has(session.CapScreenshot) || st.Sessions[0].TrackID != vn.TrackID {
		t.Fatalf("bad sessions %+v", st.Sessions)
	}
	if !strings.Contains(string(b), `"lastError":null`) {
		t.Fatalf("http source should have lastError null: %s", b)
	}
}

func TestV1IsGone(t *testing.T) {
	e := newEnv(t, "", false)
	if resp, b := e.do(t, "GET", "/api/status", "", "", ""); resp.StatusCode != 410 || !strings.Contains(string(b), "v1") {
		t.Fatalf("v1 path: %d %s", resp.StatusCode, b)
	}
	if resp, b := e.do(t, "GET", "/v2/nope", "", "", ""); resp.StatusCode != 404 || !strings.Contains(string(b), `"error"`) {
		t.Fatalf("unknown v2 path: %d %s", resp.StatusCode, b)
	}
}

func TestIngestLines(t *testing.T) {
	e := newEnv(t, "", false)
	resp, b := e.do(t, "POST", "/v2/ingest/lines", "text/plain; charset=utf-8", "  「こんにちは」 \n", "")
	if resp.StatusCode != 201 {
		t.Fatalf("plain: %d %s", resp.StatusCode, b)
	}
	if c := decode[session.Cue](t, b); c.ID != 1 || c.Text != "「こんにちは」" || c.At == 0 || c.StartMs != nil {
		t.Fatalf("bad cue %+v", c)
	}
	resp, b = e.do(t, "POST", "/v2/ingest/lines", "text/plain", "「こんにちは」", "")
	if resp.StatusCode != 200 || strings.TrimSpace(string(b)) != `{"duplicate":true}` {
		t.Fatalf("duplicate: %d %s", resp.StatusCode, b)
	}
	resp, _ = e.do(t, "POST", "/v2/ingest/lines", "application/json", `{"text":"次","source":"myhook"}`, "")
	if resp.StatusCode != 201 {
		t.Fatalf("json: %d", resp.StatusCode)
	}
	for _, c := range []struct{ ctype, body string }{
		{"text/plain", ""}, {"text/plain", "  \n "}, {"application/json", `{"text":""}`}, {"application/json", `{bad`},
	} {
		resp, b := e.do(t, "POST", "/v2/ingest/lines", c.ctype, c.body, "")
		if resp.StatusCode != 400 || !strings.Contains(string(b), `"error"`) {
			t.Errorf("%q: %d %s", c.body, resp.StatusCode, b)
		}
	}
	snap := e.reg.Snapshot()
	if len(snap) != 1 || len(snap[0].Cues) != 2 || snap[0].Cues[1].Text != "次" {
		t.Fatalf("vn session cues %+v", snap)
	}
}

func TestAuth(t *testing.T) {
	e := newEnv(t, "s3cret", false)
	for _, p := range []string{"/v2/status", "/v2/hook/threads"} {
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
	if resp, _ := e.do(t, "GET", "/v2/status?token=s3cret", "", "", ""); resp.StatusCode != 401 {
		t.Errorf("query token must only work for /v2/ws, got %d", resp.StatusCode)
	}
	if resp, _ := e.do(t, "POST", "/v2/sessions/vn/screenshot", "", "", ""); resp.StatusCode != 401 {
		t.Errorf("screenshot without token: %d", resp.StatusCode)
	}
	if resp, b := e.do(t, "GET", "/", "", "", ""); resp.StatusCode != 200 || !bytes.Contains(b, []byte("<html")) {
		t.Errorf("debug page: %d", resp.StatusCode)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	wsURL := "ws" + strings.TrimPrefix(e.ts.URL, "http") + "/v2/ws"
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

func TestVNScreenshotCaptureAndCache(t *testing.T) {
	e := newEnv(t, "", true)
	e.vn.Register(nil)
	resp, b := e.do(t, "POST", "/v2/sessions/vn/screenshot", "", "", "")
	if resp.StatusCode != 200 || resp.Header.Get("Content-Type") != "image/jpeg" ||
		resp.Header.Get("X-Screenshot-Captured-At") != "1759600000123" {
		t.Fatalf("capture: %d %v %s", resp.StatusCode, resp.Header, b)
	}
	img, err := jpeg.Decode(bytes.NewReader(b))
	if err != nil || img.Bounds().Dx() != 1280 || img.Bounds().Dy() != 800 {
		t.Fatalf("default maxWidth: %v %v", err, img)
	}
	_, b = e.do(t, "POST", "/v2/sessions/vn/screenshot", "application/json", `{"maxWidth":400,"quality":50}`, "")
	if img, _ = jpeg.Decode(bytes.NewReader(b)); img.Bounds().Dx() != 400 || img.Bounds().Dy() != 250 {
		t.Fatalf("maxWidth=400: %v", img.Bounds())
	}
	_, b = e.do(t, "POST", "/v2/sessions/vn/screenshot", "application/json", `{"maxWidth":0}`, "")
	if img, _ = jpeg.Decode(bytes.NewReader(b)); img.Bounds().Dx() != 1600 {
		t.Fatalf("maxWidth=0: %v", img.Bounds())
	}
	for _, q := range []string{`{"quality":0}`, `{"quality":101}`, `{"maxWidth":-1}`, `{"nope":1}`, `{bad`} {
		if resp, _ := e.do(t, "POST", "/v2/sessions/vn/screenshot", "application/json", q, ""); resp.StatusCode != 400 {
			t.Errorf("%s: %d", q, resp.StatusCode)
		}
	}

	l, _, _ := e.store.Add("台詞", "t")
	var buf bytes.Buffer
	jpeg.Encode(&buf, solid(640, 400, color.RGBA{0, 0, 255, 255}), &jpeg.Options{Quality: 95})
	e.cache.Put(screenshot.Cached{LineID: l.ID, JPEG: buf.Bytes(), CapturedAt: time.UnixMilli(42)})
	calls := e.shots.calls
	resp, b = e.do(t, "POST", "/v2/sessions/vn/screenshot", "application/json", fmt.Sprintf(`{"cueId":%d}`, l.ID), "")
	if resp.StatusCode != 200 || resp.Header.Get("X-Screenshot-Cue-Id") != "1" || resp.Header.Get("X-Screenshot-Captured-At") != "42" {
		t.Fatalf("cached: %d %v", resp.StatusCode, resp.Header)
	}
	img, _ = jpeg.Decode(bytes.NewReader(b))
	if r, _, bl, _ := img.At(10, 10).RGBA(); bl < 0xc000 || r > 0x4000 {
		t.Fatal("expected the cached (blue) image")
	}
	if e.shots.calls != calls {
		t.Fatal("cached screenshot must not trigger a capture")
	}
	if resp, _ := e.do(t, "POST", "/v2/sessions/vn/screenshot", "application/json", `{"cueId":99}`, ""); resp.StatusCode != 200 || e.shots.calls != calls+1 {
		t.Fatalf("unknown cue falls back to capturing: %d", resp.StatusCode)
	}
}

func TestVNScreenshotUnavailable(t *testing.T) {
	e := newEnv(t, "", false)
	e.vn.Register(nil)
	if resp, b := e.do(t, "POST", "/v2/sessions/vn/screenshot", "", "", ""); resp.StatusCode != 501 {
		t.Fatalf("no screenshot capability: %d %s", resp.StatusCode, b)
	}
	e2 := newEnv(t, "", true)
	e2.vn.Register(nil)
	e2.shots.err = fmt.Errorf("%w (gamescope: no socket)", screenshot.ErrUnavailable)
	if resp, b := e2.do(t, "POST", "/v2/sessions/vn/screenshot", "", "", ""); resp.StatusCode != 503 || !strings.Contains(string(b), "no socket") {
		t.Fatalf("failing backend: %d %s", resp.StatusCode, b)
	}
	if resp, _ := e2.do(t, "POST", "/v2/sessions/nope/screenshot", "", "", ""); resp.StatusCode != 404 {
		t.Fatalf("unknown session: %d", resp.StatusCode)
	}
	if resp, _ := e2.do(t, "POST", "/v2/sessions/vn/control", "application/json", `{"action":"pause"}`, ""); resp.StatusCode != 501 {
		t.Fatalf("vn control: %d", resp.StatusCode)
	}
}

func TestVideoCommands(t *testing.T) {
	e := newEnv(t, "", false)
	f := &fakeVideo{}
	e.addVideo(f)

	for _, body := range []string{`{"action":"pause"}`, `{"action":"play"}`, `{"action":"seek","ms":4000}`} {
		if resp, b := e.do(t, "POST", "/v2/sessions/mpv/control", "application/json", body, ""); resp.StatusCode != 200 {
			t.Fatalf("%s: %d %s", body, resp.StatusCode, b)
		}
	}
	if strings.Join(f.controls, ",") != "pause,play,seek 4000" {
		t.Fatalf("controls %v", f.controls)
	}
	for _, body := range []string{`{"action":"stop"}`, `{"action":"seek"}`, `{"action":"seek","ms":-1}`} {
		if resp, _ := e.do(t, "POST", "/v2/sessions/mpv/control", "application/json", body, ""); resp.StatusCode != 400 {
			t.Errorf("%s: %d", body, resp.StatusCode)
		}
	}

	// A cue resolves to its midpoint on the bridge.
	resp, b := e.do(t, "POST", "/v2/sessions/mpv/screenshot", "application/json", `{"trackId":"t1","cueId":1,"maxWidth":640}`, "")
	if resp.StatusCode != 200 || string(b) != "jpeg" {
		t.Fatalf("screenshot: %d %s", resp.StatusCode, b)
	}
	if r := f.shots[0]; r.AtMs == nil || *r.AtMs != 2000 || r.MaxWidth != 640 || r.Cue == nil || r.Cue.ID != 1 {
		t.Fatalf("screenshot request %+v", r)
	}
	// Stale track ids are rejected; unknown cues are 404.
	if resp, _ := e.do(t, "POST", "/v2/sessions/mpv/screenshot", "application/json", `{"trackId":"old","cueId":1}`, ""); resp.StatusCode != 409 {
		t.Fatalf("stale track: %d", resp.StatusCode)
	}
	if resp, _ := e.do(t, "POST", "/v2/sessions/mpv/audio", "application/json", `{"cueId":42}`, ""); resp.StatusCode != 404 {
		t.Fatalf("unknown cue: %d", resp.StatusCode)
	}

	resp, b = e.do(t, "POST", "/v2/sessions/mpv/audio", "application/json", `{"trackId":"t1","cueId":2,"padMs":300}`, "")
	if resp.StatusCode != 200 || resp.Header.Get("Content-Type") != "audio/mpeg" || string(b) != "mp3" {
		t.Fatalf("audio: %d %s", resp.StatusCode, b)
	}
	if r := f.ranges[0]; r.StartMs != 4000 || r.EndMs != 20000 || r.PadMs != 300 {
		t.Fatalf("audio range %+v", r)
	}
	resp, _ = e.do(t, "POST", "/v2/sessions/mpv/clip", "application/json", `{"cueId":2}`, "")
	if resp.StatusCode != 200 || resp.Header.Get("Content-Type") != "image/webp" {
		t.Fatalf("clip: %d", resp.StatusCode)
	}
	// Clips are capped at 6 s and use the defaults.
	if r := f.ranges[1]; r.StartMs != 4000 || r.EndMs != 10000 || r.MaxWidth != 480 || r.FPS != 12 || r.PadMs != 0 {
		t.Fatalf("clip range %+v", r)
	}
	if resp, _ := e.do(t, "POST", "/v2/sessions/mpv/audio", "application/json", `{"startMs":500,"endMs":900}`, ""); resp.StatusCode != 200 {
		t.Fatalf("explicit range: %d", resp.StatusCode)
	}
	for _, body := range []string{`{}`, `{"startMs":900,"endMs":500}`, `{"startMs":-5,"endMs":5}`} {
		if resp, _ := e.do(t, "POST", "/v2/sessions/mpv/audio", "application/json", body, ""); resp.StatusCode != 400 {
			t.Errorf("%s: %d", body, resp.StatusCode)
		}
	}
	if resp, _ := e.do(t, "POST", "/v2/sessions/mpv/clip", "application/json", `{"cueId":1,"fps":99}`, ""); resp.StatusCode != 400 {
		t.Errorf("fps: %d", resp.StatusCode)
	}
	f.err = errors.New("ffmpeg exploded")
	if resp, b := e.do(t, "POST", "/v2/sessions/mpv/clip", "application/json", `{"cueId":1}`, ""); resp.StatusCode != 503 || !strings.Contains(string(b), "ffmpeg exploded") {
		t.Fatalf("failure: %d %s", resp.StatusCode, b)
	}
}

func TestIngestSessions(t *testing.T) {
	e := newEnv(t, "", false)
	resp, b := e.do(t, "POST", "/v2/ingest/sessions/browser", "application/json",
		`{"kind":"web","title":"YouTube","mode":"replace","cues":[{"text":"あ","startMs":0,"endMs":1000},{"text":"い","startMs":1000,"endMs":2000}],"playback":{"positionMs":500,"paused":false,"speed":1}}`, "")
	if resp.StatusCode != 200 {
		t.Fatalf("ingest: %d %s", resp.StatusCode, b)
	}
	if info := decode[session.Info](t, b); info.Kind != session.KindWeb || info.Title != "YouTube" || len(info.Capabilities) != 0 {
		t.Fatalf("info %+v", info)
	}
	e.do(t, "POST", "/v2/ingest/sessions/browser", "application/json", `{"kind":"web","title":"YouTube","cues":[{"text":"う"}]}`, "")
	var got *session.Snapshot
	for _, s := range e.reg.Snapshot() {
		if s.ID == "browser" {
			got = &s
		}
	}
	if got == nil || len(got.Cues) != 3 || got.Cues[0].ID != 1 || got.Cues[2].ID != 3 || got.Playback == nil || got.Playback.PositionMs != 500 {
		t.Fatalf("ingest session %+v", got)
	}
	if resp, _ := e.do(t, "POST", "/v2/ingest/sessions/mpv", "application/json", `{}`, ""); resp.StatusCode != 409 {
		t.Fatalf("reserved id: %d", resp.StatusCode)
	}
	if resp, _ := e.do(t, "POST", "/v2/ingest/sessions/x", "application/json", `{"kind":"tv"}`, ""); resp.StatusCode != 400 {
		t.Fatalf("bad kind: %d", resp.StatusCode)
	}
	if resp, _ := e.do(t, "POST", "/v2/sessions/browser/control", "application/json", `{"action":"pause"}`, ""); resp.StatusCode != 501 {
		t.Fatalf("ingest sessions have no commands: %d", resp.StatusCode)
	}
	if resp, _ := e.do(t, "DELETE", "/v2/ingest/sessions/browser", "", "", ""); resp.StatusCode != 200 {
		t.Fatalf("end: %d", resp.StatusCode)
	}
	if _, _, ok := e.reg.Get("browser"); ok {
		t.Fatal("session should be gone")
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
	resp, b := e.do(t, "POST", "/v2/hook/threads/select", "application/json", `{"threadId":null}`, "secret")
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
	resp, b = e.do(t, "GET", "/v2/hook/threads", "", "", "secret")
	if resp.StatusCode != 200 || !strings.Contains(string(b), `"selectedThreadId":null`) || !strings.Contains(string(b), "「こんにちは」") {
		t.Fatalf("threads %d %s", resp.StatusCode, b)
	}
	if resp, _ = e.do(t, "POST", "/v2/hook/threads/select", "application/json", `{"threadId":"missing"}`, "secret"); resp.StatusCode != 404 {
		t.Fatalf("unknown %d", resp.StatusCode)
	}
	resp, b = e.do(t, "POST", "/v2/hook/threads/select", "application/json", `{"threadId":"t1"}`, "secret")
	if resp.StatusCode != 200 || f.snap.SelectedID != "t1" || !strings.Contains(string(b), `"mode":"manual"`) {
		t.Fatalf("select %d %s", resp.StatusCode, b)
	}
	if resp, _ = e.do(t, "POST", "/v2/hook/threads/select", "application/json", `{"threadId":null}`, "secret"); resp.StatusCode != 200 || !f.nilSelect {
		t.Fatalf("back to auto %d", resp.StatusCode)
	}
	if resp, _ = e.do(t, "POST", "/v2/hook/threads/select", "application/json", `{"nope":1}`, "secret"); resp.StatusCode != 400 {
		t.Fatalf("bad body %d", resp.StatusCode)
	}

	c, ctx := dialWS(t, e, "?token=secret")
	hello := readMsg(t, ctx, c)
	if hello.Type != "hello" || hello.Bridge == nil || hello.Bridge.Hook.State != hook.StateAttached {
		t.Fatalf("hello hook %+v", hello.Bridge)
	}
	e.hub.Publish(ThreadsChanged{})
	typ, body, err := c.Read(ctx)
	if err != nil || typ != websocket.MessageText || !strings.Contains(string(body), `"type":"threads"`) {
		t.Fatalf("threads frame %s err=%v", body, err)
	}
}
