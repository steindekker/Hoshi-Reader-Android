package sources

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func TestExtractText(t *testing.T) {
	cases := map[string]string{
		"「おはよう」":                                    "「おはよう」",
		`{"sentence":"文です","process_path":"x.exe"}`: "文です",
		`{"text":"テキスト"}`:                           "テキスト",
		`{"line":"行"}`:                              "行",
		`{"sentence":5,"text":"fallback"}`:          "fallback",
		`"引用された文字列"`:                                "引用された文字列",
		`{"other":"x"}`:                             `{"other":"x"}`,
		`{not json`:                                 `{not json`,
		`["array"]`:                                 `["array"]`,
		`123`:                                       `123`,
		"":                                          "",
	}
	for in, want := range cases {
		if got := ExtractText([]byte(in)); got != want {
			t.Errorf("ExtractText(%q) = %q, want %q", in, got, want)
		}
	}
}

type collector struct {
	mu      sync.Mutex
	texts   []string
	reports []bool
	errs    []error
}

func (c *collector) emit(s string) { c.mu.Lock(); c.texts = append(c.texts, s); c.mu.Unlock() }
func (c *collector) report(ok bool, err error) {
	c.mu.Lock()
	c.reports = append(c.reports, ok)
	c.errs = append(c.errs, err)
	c.mu.Unlock()
}
func (c *collector) snapshot() ([]string, []bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]string(nil), c.texts...), append([]bool(nil), c.reports...)
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

// fakeHooker is a WebSocket server like the Textractor extension: it pushes
// queued messages to whoever connects, and can drop connections.
type fakeHooker struct {
	mu    sync.Mutex
	conns []*websocket.Conn
	sends chan string
}

func (f *fakeHooker) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	c, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	f.mu.Lock()
	f.conns = append(f.conns, c)
	f.mu.Unlock()
	ctx := c.CloseRead(r.Context())
	for {
		select {
		case <-ctx.Done():
			return
		case msg := <-f.sends:
			if err := c.Write(ctx, websocket.MessageText, []byte(msg)); err != nil {
				return
			}
		}
	}
}

func (f *fakeHooker) dropAll() {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, c := range f.conns {
		c.CloseNow()
	}
	f.conns = nil
}

func (f *fakeHooker) connCount() int { f.mu.Lock(); defer f.mu.Unlock(); return len(f.conns) }

func TestWebSocketSourcePlainJSONAndReconnect(t *testing.T) {
	hooker := &fakeHooker{sends: make(chan string)}
	ts := httptest.NewServer(hooker)
	defer ts.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	col := &collector{}
	src := &WebSocket{URL: "ws" + strings.TrimPrefix(ts.URL, "http"), Backoff: Backoff{Min: 20 * time.Millisecond, Max: 50 * time.Millisecond}}
	done := make(chan struct{})
	go func() { src.Run(ctx, col.emit, col.report); close(done) }()

	hooker.sends <- "「プレーンテキスト」"
	hooker.sends <- `{"sentence":"JSONの文"}`
	waitFor(t, "two texts", func() bool { txt, _ := col.snapshot(); return len(txt) == 2 })
	txt, rep := col.snapshot()
	if txt[0] != "「プレーンテキスト」" || txt[1] != "JSONの文" {
		t.Fatalf("texts = %q", txt)
	}
	if len(rep) != 1 || !rep[0] {
		t.Fatalf("expected a single connected report, got %v", rep)
	}

	hooker.dropAll()
	waitFor(t, "reconnect", func() bool { return hooker.connCount() == 1 })
	hooker.sends <- "再接続後"
	waitFor(t, "third text", func() bool { txt, _ := col.snapshot(); return len(txt) == 3 })
	_, rep = col.snapshot()
	if len(rep) < 3 || rep[1] || !rep[len(rep)-1] {
		t.Fatalf("expected connected, disconnected, connected; got %v", rep)
	}

	cancel()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("Run did not stop on cancel")
	}
}

func TestWebSocketSourceReportsUnreachable(t *testing.T) {
	ts := httptest.NewServer(http.NotFoundHandler())
	url := "ws" + strings.TrimPrefix(ts.URL, "http")
	ts.Close() // nothing listening any more

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	col := &collector{}
	src := &WebSocket{URL: url, Backoff: Backoff{Min: 10 * time.Millisecond, Max: 20 * time.Millisecond}}
	go src.Run(ctx, col.emit, col.report)
	waitFor(t, "error report", func() bool { _, r := col.snapshot(); return len(r) >= 2 })
	col.mu.Lock()
	defer col.mu.Unlock()
	if col.reports[0] || col.errs[0] == nil {
		t.Fatalf("expected disconnected with error, got %v %v", col.reports[0], col.errs[0])
	}
}

func TestBackoff(t *testing.T) {
	b := Backoff{Min: time.Second, Max: 4 * time.Second}
	var got []time.Duration
	for i := 0; i < 5; i++ {
		got = append(got, b.Next())
	}
	want := []time.Duration{1, 2, 4, 4, 4}
	for i := range want {
		if got[i] != want[i]*time.Second {
			t.Fatalf("backoff = %v", got)
		}
	}
	b.Reset()
	if b.Next() != time.Second {
		t.Fatal("reset did not restart at Min")
	}
}

func TestClipboardCommandSource(t *testing.T) {
	dir := t.TempDir()
	clip := filepath.Join(dir, "clip.txt")
	write := func(s string) {
		if err := os.WriteFile(clip+".tmp", []byte(s), 0o644); err != nil {
			t.Fatal(err)
		}
		os.Rename(clip+".tmp", clip)
	}
	write("起動前の内容")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	col := &collector{}
	src := &Clipboard{
		Open:     OpenClipboard("cat '"+clip+"'", "", nil),
		Interval: 20 * time.Millisecond,
		Backoff:  Backoff{Min: 20 * time.Millisecond, Max: 50 * time.Millisecond},
	}
	go src.Run(ctx, col.emit, col.report)
	waitFor(t, "connected", func() bool { _, r := col.snapshot(); return len(r) == 1 && r[0] })
	time.Sleep(100 * time.Millisecond)
	if txt, _ := col.snapshot(); len(txt) != 0 {
		t.Fatalf("startup content must be a baseline, not emitted: %q", txt)
	}

	write("新しい文")
	waitFor(t, "first change", func() bool { txt, _ := col.snapshot(); return len(txt) == 1 })
	time.Sleep(100 * time.Millisecond) // unchanged content is not re-emitted
	write("  ")                        // whitespace-only change is ignored
	time.Sleep(100 * time.Millisecond)
	write("次の文")
	waitFor(t, "second change", func() bool { txt, _ := col.snapshot(); return len(txt) == 2 })
	txt, _ := col.snapshot()
	if txt[0] != "新しい文" || txt[1] != "次の文" {
		t.Fatalf("texts = %q", txt)
	}

	// A failing command reports disconnected with the error.
	os.Remove(clip)
	waitFor(t, "failure report", func() bool { _, r := col.snapshot(); return len(r) >= 2 && !r[len(r)-1] })
	write("復活")
	waitFor(t, "recovered", func() bool { _, r := col.snapshot(); return r[len(r)-1] })
}

func TestCommandClipboardEmptyIsNotAnError(t *testing.T) {
	c := &CommandClipboard{Command: `echo "Nothing is copied" >&2; exit 1`}
	got, err := c.Read(context.Background())
	if err != nil || got != "" {
		t.Fatalf("got %q, %v", got, err)
	}
	c = &CommandClipboard{Command: `echo "cannot open display" >&2; exit 1`}
	if _, err := c.Read(context.Background()); err == nil || !strings.Contains(err.Error(), "cannot open display") {
		t.Fatalf("expected error with stderr, got %v", err)
	}
}
