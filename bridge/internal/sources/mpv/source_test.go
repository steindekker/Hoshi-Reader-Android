package mpv

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"image"
	"image/color"
	"image/png"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/steindekker/hoshi-reader/bridge/internal/hub"
	"github.com/steindekker/hoshi-reader/bridge/internal/media"
	"github.com/steindekker/hoshi-reader/bridge/internal/session"
)

// fakeMPV is a scripted mpv JSON IPC server.
type fakeMPV struct {
	t        *testing.T
	ln       net.Listener
	path     string
	mu       sync.Mutex
	props    map[string]any
	observed map[string]int64
	conns    []net.Conn
	commands []string
}

func newFakeMPV(t *testing.T, props map[string]any) *fakeMPV {
	t.Helper()
	dir, err := os.MkdirTemp("", "mpvt")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	f := &fakeMPV{t: t, path: filepath.Join(dir, "sock"), props: props, observed: map[string]int64{}}
	f.ln, err = net.Listen("unix", f.path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(f.close)
	go f.accept()
	return f
}

func (f *fakeMPV) close() {
	f.ln.Close()
	f.mu.Lock()
	for _, c := range f.conns {
		c.Close()
	}
	f.mu.Unlock()
}

func (f *fakeMPV) accept() {
	for {
		c, err := f.ln.Accept()
		if err != nil {
			return
		}
		f.mu.Lock()
		f.conns = append(f.conns, c)
		f.mu.Unlock()
		go f.serve(c)
	}
}

func (f *fakeMPV) send(c net.Conn, v any) {
	b, _ := json.Marshal(v)
	c.Write(append(b, '\n'))
}

func (f *fakeMPV) broadcast(v any) {
	f.mu.Lock()
	conns := append([]net.Conn{}, f.conns...)
	f.mu.Unlock()
	for _, c := range conns {
		f.send(c, v)
	}
}

// set changes a property and notifies observers.
func (f *fakeMPV) set(name string, v any) {
	f.mu.Lock()
	f.props[name] = v
	id, ok := f.observed[name]
	f.mu.Unlock()
	if ok {
		f.broadcast(map[string]any{"event": "property-change", "id": id, "name": name, "data": v})
	}
}

func (f *fakeMPV) commandLog() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string{}, f.commands...)
}

func (f *fakeMPV) serve(c net.Conn) {
	sc := bufio.NewScanner(c)
	for sc.Scan() {
		var req struct {
			Command   []any `json:"command"`
			RequestID int64 `json:"request_id"`
		}
		if json.Unmarshal(sc.Bytes(), &req) != nil || len(req.Command) == 0 {
			continue
		}
		name, _ := req.Command[0].(string)
		f.mu.Lock()
		f.commands = append(f.commands, strings.TrimSpace(strings.Trim(strings.ReplaceAll(jsonString(req.Command), `"`, ""), "[]")))
		f.mu.Unlock()
		reply := map[string]any{"error": "success", "request_id": req.RequestID}
		switch name {
		case "get_property":
			f.mu.Lock()
			v, ok := f.props[req.Command[1].(string)]
			f.mu.Unlock()
			if !ok {
				reply["error"] = "property unavailable"
			} else {
				reply["data"] = v
			}
			f.send(c, reply)
		case "observe_property":
			prop := req.Command[2].(string)
			id := int64(req.Command[1].(float64))
			f.mu.Lock()
			f.observed[prop] = id
			v, ok := f.props[prop]
			f.mu.Unlock()
			f.send(c, reply)
			if !ok {
				v = nil
			}
			f.send(c, map[string]any{"event": "property-change", "id": id, "name": prop, "data": v})
		case "set_property":
			f.send(c, reply)
			f.set(req.Command[1].(string), req.Command[2])
		case "seek":
			f.send(c, reply)
			f.broadcast(map[string]any{"event": "seek"})
			f.set("time-pos", req.Command[1])
			f.broadcast(map[string]any{"event": "playback-restart"})
		case "screenshot-to-file":
			img := image.NewRGBA(image.Rect(0, 0, 320, 180))
			for i := range img.Pix {
				img.Pix[i] = 0xff
			}
			var buf bytes.Buffer
			png.Encode(&buf, img)
			os.WriteFile(req.Command[1].(string), buf.Bytes(), 0o644)
			f.send(c, reply)
		default:
			reply["error"] = "invalid command"
			f.send(c, reply)
		}
	}
}

func jsonString(v any) string { b, _ := json.Marshal(v); return string(b) }

type harness struct {
	reg    *session.Registry
	src    *Source
	cancel context.CancelFunc
	done   chan struct{}
}

func start(t *testing.T, f *fakeMPV, tools *media.Tools) *harness {
	t.Helper()
	reg := session.NewRegistry(hub.New())
	if tools == nil {
		tools = &media.Tools{WorkDir: t.TempDir()}
	}
	src := New(Options{
		Socket: f.path, Registry: reg, Media: tools, CacheDir: t.TempDir(),
		Poll: 50 * time.Millisecond, Heartbeat: 100 * time.Millisecond, Debounce: 20 * time.Millisecond,
	})
	ctx, cancel := context.WithCancel(context.Background())
	h := &harness{reg: reg, src: src, cancel: cancel, done: make(chan struct{})}
	go func() { src.Run(ctx); close(h.done) }()
	t.Cleanup(func() { cancel(); <-h.done })
	return h
}

func (h *harness) waitFor(t *testing.T, what string, ok func(s *session.Snapshot) bool) session.Snapshot {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		for _, s := range h.reg.Snapshot() {
			if s.ID == SessionID && ok(&s) {
				return s
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s; sessions: %+v", what, h.reg.Snapshot())
	return session.Snapshot{}
}

func writeSRT(t *testing.T, dir, name string) string {
	t.Helper()
	p := filepath.Join(dir, name)
	os.WriteFile(p, []byte("1\n00:00:01,000 --> 00:00:02,000\n一行目\n\n2\n00:00:04,000 --> 00:00:06,000\n二行目\n"), 0o644)
	return p
}

func TestSessionFromExternalSubtitles(t *testing.T) {
	dir := t.TempDir()
	srt := writeSRT(t, dir, "ep01.ja.srt")
	f := newFakeMPV(t, map[string]any{
		"path": "/media/ep01.mkv", "media-title": "Frieren 01", "working-directory": dir,
		"current-tracks/sub": map[string]any{"id": 2, "codec": "subrip", "external": true, "external-filename": "ep01.ja.srt", "lang": "ja"},
		"sub-delay":          0.5, "time-pos": 1.25, "pause": false, "speed": 1.0, "idle-active": false,
	})
	_ = srt
	h := start(t, f, nil)

	s := h.waitFor(t, "cues", func(s *session.Snapshot) bool { return len(s.Cues) == 2 })
	if s.Kind != session.KindVideo || s.Title != "Frieren 01" || !s.Has(session.CapControl) || !s.Has(session.CapClip) || s.Source != "mpv" {
		t.Fatalf("session info %+v", s.Info)
	}
	if c := s.Cues[0]; c.ID != 1 || c.Text != "一行目" || *c.StartMs != 1500 || *c.EndMs != 2500 {
		t.Fatalf("delayed cue %+v", c)
	}
	if s.Playback == nil || s.Playback.PositionMs != 1250 || s.Playback.Paused {
		t.Fatalf("playback %+v", s.Playback)
	}
	firstTrack := s.TrackID

	f.set("sub-delay", 0.0)
	s = h.waitFor(t, "re-timed cues", func(s *session.Snapshot) bool { return s.TrackID != firstTrack && len(s.Cues) == 2 })
	if *s.Cues[1].StartMs != 4000 {
		t.Fatalf("sub-delay change must re-time cues: %+v", s.Cues[1])
	}

	ctx := context.Background()
	if err := h.src.Control(ctx, "pause", nil); err != nil {
		t.Fatal(err)
	}
	h.waitFor(t, "paused", func(s *session.Snapshot) bool { return s.Playback != nil && s.Playback.Paused })
	ms := int64(4000)
	if err := h.src.Control(ctx, "seek", &ms); err != nil {
		t.Fatal(err)
	}
	h.waitFor(t, "seek", func(s *session.Snapshot) bool { return s.Playback != nil && s.Playback.PositionMs == 4000 })
	log := strings.Join(f.commandLog(), "\n")
	if !strings.Contains(log, "set_property,pause,true") || !strings.Contains(log, "seek,4,absolute") {
		t.Fatalf("commands:\n%s", log)
	}

	// Paused inside the cue: the displayed frame comes from mpv itself.
	cue := s.Cues[1]
	shot, err := h.src.Screenshot(ctx, session.ScreenshotRequest{Cue: &cue, AtMs: session.Ms(5000), MaxWidth: 160, Quality: 80})
	if err != nil || shot.ContentType != "image/jpeg" || len(shot.Data) == 0 {
		t.Fatalf("screenshot: %v %+v", err, shot.ContentType)
	}
	if !strings.Contains(strings.Join(f.commandLog(), "\n"), "screenshot-to-file") {
		t.Fatal("expected screenshot-to-file while paused inside the cue")
	}

	f.set("media-title", "Frieren 02")
	h.waitFor(t, "title", func(s *session.Snapshot) bool { return s.Title == "Frieren 02" })

	f.set("idle-active", true)
	deadline := time.Now().Add(3 * time.Second)
	for len(h.reg.Snapshot()) != 0 {
		if time.Now().After(deadline) {
			t.Fatal("idle mpv must end the session")
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestReconnectsWhenMPVRestarts(t *testing.T) {
	dir := t.TempDir()
	srt := writeSRT(t, dir, "a.srt")
	props := map[string]any{
		"path": "/media/a.mkv", "current-tracks/sub": map[string]any{"id": 1, "codec": "subrip", "external": true, "external-filename": srt},
		"time-pos": 0.0, "pause": true, "speed": 1.0,
	}
	f := newFakeMPV(t, props)
	h := start(t, f, nil)
	h.waitFor(t, "session", func(s *session.Snapshot) bool { return len(s.Cues) == 2 })
	if s := h.reg.Snapshot()[0]; s.Title != "a.mkv" {
		t.Fatalf("title falls back to the file name: %q", s.Title)
	}
	f.close()
	deadline := time.Now().Add(3 * time.Second)
	for len(h.reg.Snapshot()) != 0 {
		if time.Now().After(deadline) {
			t.Fatal("session must end when mpv quits")
		}
		time.Sleep(10 * time.Millisecond)
	}
	// mpv starts again on the same socket path.
	os.Remove(f.path)
	ln, err := net.Listen("unix", f.path)
	if err != nil {
		t.Fatal(err)
	}
	f2 := &fakeMPV{t: t, ln: ln, path: f.path, props: props, observed: map[string]int64{}}
	t.Cleanup(f2.close)
	go f2.accept()
	h.waitFor(t, "session after restart", func(s *session.Snapshot) bool { return len(s.Cues) == 2 })
}

func TestLiveFallbackForImageSubtitles(t *testing.T) {
	f := newFakeMPV(t, map[string]any{
		"path": "bd://1", "media-title": "Disc",
		"current-tracks/sub": map[string]any{"id": 3, "codec": "hdmv_pgs_subtitle", "external": false, "ff-index": 4, "lang": "jpn"},
		"time-pos":           10.0, "pause": false, "speed": 1.0, "sub-delay": 0.2,
	})
	h := start(t, f, nil)
	s := h.waitFor(t, "live session", func(s *session.Snapshot) bool { return s.TrackID != "" })
	if len(s.Cues) != 0 {
		t.Fatalf("live track starts empty: %+v", s.Cues)
	}
	f.set("sub-start", 10.5)
	f.set("sub-end", 12.0)
	f.set("sub-text", "生きてる")
	s = h.waitFor(t, "live cue", func(s *session.Snapshot) bool { return len(s.Cues) == 1 })
	if c := s.Cues[0]; c.Text != "生きてる" || c.ID != 1 || c.StartMs == nil || *c.StartMs != 10700 || *c.EndMs != 12200 {
		t.Fatalf("live cue %+v", c)
	}
	f.set("sub-text", "")
	f.set("sub-text", "生きてる")
	h.waitFor(t, "repeat after a gap", func(s *session.Snapshot) bool { return len(s.Cues) == 2 && s.Cues[1].ID == 2 })
}

func TestOCRSubtitlesNextToTheVideo(t *testing.T) {
	dir := t.TempDir()
	video := filepath.Join(dir, "ep.mkv")
	os.WriteFile(video, []byte("not really"), 0o644)
	writeSRT(t, dir, "ep.jpn.ocr4.srt")
	f := newFakeMPV(t, map[string]any{
		"path":               video,
		"current-tracks/sub": map[string]any{"id": 3, "codec": "hdmv_pgs_subtitle", "external": false, "ff-index": 4, "lang": "jpn"},
		"time-pos":           0.0, "pause": true, "speed": 1.0,
	})
	h := start(t, f, nil)
	s := h.waitFor(t, "ocr cues", func(s *session.Snapshot) bool { return len(s.Cues) == 2 })
	if s.Cues[1].Text != "二行目" {
		t.Fatalf("cues %+v", s.Cues)
	}
}

// testVideo muxes a short video with an audio track and an embedded SRT track.
func testVideo(t *testing.T) string {
	t.Helper()
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}
	dir := t.TempDir()
	srt := writeSRT(t, dir, "in.srt")
	out := filepath.Join(dir, "embedded.mkv")
	cmd := exec.Command("ffmpeg", "-hide_banner", "-v", "error", "-y",
		"-f", "lavfi", "-i", "testsrc2=size=640x360:rate=24:duration=7",
		"-f", "lavfi", "-i", "sine=frequency=440:duration=7",
		"-i", srt, "-map", "0:v", "-map", "1:a", "-map", "2:s",
		"-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-c:s", "srt", out)
	if b, err := cmd.CombinedOutput(); err != nil {
		t.Skipf("cannot build test video: %v %s", err, b)
	}
	return out
}

func TestEmbeddedTrackAndMedia(t *testing.T) {
	video := testVideo(t)
	f := newFakeMPV(t, map[string]any{
		"path": video, "stream-open-filename": video, "media-title": "Embedded",
		"current-tracks/sub":   map[string]any{"id": 1, "codec": "subrip", "external": false, "ff-index": 2},
		"current-tracks/audio": map[string]any{"id": 1, "external": false, "ff-index": 1},
		"time-pos":             0.5, "pause": false, "speed": 1.0,
	})
	h := start(t, f, nil)
	s := h.waitFor(t, "extracted cues", func(s *session.Snapshot) bool { return len(s.Cues) == 2 })
	if *s.Cues[1].StartMs != 4000 || s.Cues[1].Text != "二行目" {
		t.Fatalf("extracted %+v", s.Cues)
	}
	ctx := context.Background()
	audio, err := h.src.Audio(ctx, session.RangeRequest{StartMs: 1000, EndMs: 2000, PadMs: 150})
	if err != nil || audio.ContentType != "audio/mpeg" || len(audio.Data) < 1000 {
		t.Fatalf("audio %v %d", err, len(audio.Data))
	}
	clip, err := h.src.Clip(ctx, session.RangeRequest{StartMs: 4000, EndMs: 6000, MaxWidth: 320, FPS: 10})
	if err != nil || !strings.HasPrefix(clip.ContentType, "image/") || len(clip.Data) == 0 {
		t.Fatalf("clip %v %s", err, clip.ContentType)
	}
	// Playing: the frame comes from the file at the requested time.
	shot, err := h.src.Screenshot(ctx, session.ScreenshotRequest{AtMs: session.Ms(5000), MaxWidth: 320, Quality: 80})
	if err != nil || shot.ContentType != "image/jpeg" {
		t.Fatalf("screenshot %v", err)
	}
	img, _, err := image.Decode(bytes.NewReader(shot.Data))
	if err != nil || img.Bounds().Dx() != 320 {
		t.Fatalf("frame %v %v", err, img)
	}
	_ = color.White
}
