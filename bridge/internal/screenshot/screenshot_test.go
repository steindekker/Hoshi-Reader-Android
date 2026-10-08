package screenshot

import (
	"bytes"
	"context"
	"errors"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

func solid(w, h int, c color.Color) *image.NRGBA {
	img := image.NewNRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, c)
		}
	}
	return img
}

func TestEncodeJPEGScaling(t *testing.T) {
	src := solid(1920, 1080, color.NRGBA{10, 200, 10, 255})
	for _, c := range []struct{ maxW, wantW, wantH int }{
		{1280, 1280, 720}, {0, 1920, 1080}, {4000, 1920, 1080}, {333, 333, 187},
	} {
		b, err := EncodeJPEG(src, c.maxW, 80)
		if err != nil {
			t.Fatal(err)
		}
		img, err := jpeg.Decode(bytes.NewReader(b))
		if err != nil {
			t.Fatal(err)
		}
		if img.Bounds().Dx() != c.wantW || img.Bounds().Dy() != c.wantH {
			t.Errorf("maxWidth %d: got %v want %dx%d", c.maxW, img.Bounds().Size(), c.wantW, c.wantH)
		}
	}
	if _, err := EncodeJPEG(src, 0, 0); err == nil {
		t.Fatal("quality 0 must fail")
	}
}

func TestEncodeJPEGTransparentBecomesBlack(t *testing.T) {
	b, err := EncodeJPEG(solid(64, 64, color.NRGBA{255, 255, 255, 0}), 0, 90)
	if err != nil {
		t.Fatal(err)
	}
	img, _ := jpeg.Decode(bytes.NewReader(b))
	if r, g, bl, _ := img.At(32, 32).RGBA(); r > 0x1000 || g > 0x1000 || bl > 0x1000 {
		t.Fatalf("transparent pixels should flatten to black, got %x %x %x", r, g, bl)
	}
}

type fakeBackend struct {
	name     string
	availErr error
	capErr   error
	img      image.Image
	calls    int
}

func (f *fakeBackend) Name() string                        { return f.name }
func (f *fakeBackend) Available(ctx context.Context) error { return f.availErr }
func (f *fakeBackend) Capture(ctx context.Context, out string) (image.Image, error) {
	f.calls++
	return f.img, f.capErr
}

func TestManagerFallsThroughChain(t *testing.T) {
	t.Setenv("XDG_RUNTIME_DIR", t.TempDir())
	a := &fakeBackend{name: "a", availErr: errors.New("not here")}
	b := &fakeBackend{name: "b", capErr: errors.New("boom")}
	c := &fakeBackend{name: "c", img: solid(10, 10, color.White)}
	m := NewManager([]Backend{a, b, c}, time.Second, quiet)
	if got := m.Detect(context.Background()); got != "b" {
		t.Fatalf("Detect = %q, want first available", got)
	}
	shot, err := m.Capture(context.Background())
	if err != nil || shot.Backend != "c" {
		t.Fatalf("Capture = %+v, %v", shot.Backend, err)
	}
	if a.calls != 0 || b.calls != 1 {
		t.Fatalf("calls a=%d b=%d", a.calls, b.calls)
	}

	m = NewManager([]Backend{a, b}, time.Second, quiet)
	_, err = m.Capture(context.Background())
	if !errors.Is(err, ErrUnavailable) || !strings.Contains(err.Error(), "boom") || !strings.Contains(err.Error(), "not here") {
		t.Fatalf("error should list reasons, got %v", err)
	}
	if m := NewManager(nil, time.Second, quiet); m.Detect(context.Background()) != "" {
		t.Fatal("empty chain detects nothing")
	}
}

func TestCustomCommandBackend(t *testing.T) {
	t.Setenv("XDG_RUNTIME_DIR", t.TempDir())
	dir := t.TempDir()
	src := filepath.Join(dir, "src image.png")
	f, _ := os.Create(src)
	png.Encode(f, solid(300, 200, color.NRGBA{0, 0, 255, 255}))
	f.Close()

	bs, err := NewBackends([]string{"custom"}, Settings{Command: "cp '" + src + "' {output}"})
	if err != nil || len(bs) != 1 {
		t.Fatalf("NewBackends: %v %d", err, len(bs))
	}
	m := NewManager(bs, 3*time.Second, quiet)
	shot, err := m.Capture(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if shot.Backend != "custom" || shot.Image.Bounds().Dx() != 300 {
		t.Fatalf("got %s %v", shot.Backend, shot.Image.Bounds())
	}

	// Async writers (the file appears after the command returns) work too.
	bs, _ = NewBackends([]string{"custom"}, Settings{Command: "(sleep 0.2; cp '" + src + "' {output}) &"})
	if _, err := NewManager(bs, 3*time.Second, quiet).Capture(context.Background()); err != nil {
		t.Fatalf("async command: %v", err)
	}

	bs, _ = NewBackends([]string{"custom"}, Settings{Command: "echo nope >&2; exit 3"})
	_, err = NewManager(bs, time.Second, quiet).Capture(context.Background())
	if err == nil || !strings.Contains(err.Error(), "nope") {
		t.Fatalf("failing command should surface stderr: %v", err)
	}

	bs, _ = NewBackends([]string{"custom"}, Settings{Command: "true"})
	start := time.Now()
	_, err = NewManager(bs, 300*time.Millisecond, quiet).Capture(context.Background())
	if err == nil || time.Since(start) > 2*time.Second {
		t.Fatalf("command writing nothing must time out quickly: %v", err)
	}
}

func TestNewBackends(t *testing.T) {
	bs, err := NewBackends(nil, Settings{})
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, b := range bs {
		names = append(names, b.Name())
	}
	// custom is skipped when no command is configured.
	if strings.Join(names, ",") != "gamescope,gamescopectl,gamescope-x11,spectacle,grim,import,ffmpeg" {
		t.Fatalf("default chain = %v", names)
	}
	if _, err := NewBackends([]string{"bogus"}, Settings{}); err == nil {
		t.Fatal("unknown backend must fail")
	}
}

func TestGamescopeX11PicksNewestFile(t *testing.T) {
	dir := t.TempDir()
	old := GamescopeTmpDir
	GamescopeTmpDir = dir
	defer func() { GamescopeTmpDir = old }()
	start := time.Now()
	os.WriteFile(filepath.Join(dir, "gamescope_2024-01-01_00-00-00.png"), nil, 0o644)
	os.Chtimes(filepath.Join(dir, "gamescope_2024-01-01_00-00-00.png"), start.Add(-time.Hour), start.Add(-time.Hour))
	if p := newestGamescopePNG(start); p != "" {
		t.Fatalf("stale file picked: %s", p)
	}
	os.WriteFile(filepath.Join(dir, "gamescope.png"), nil, 0o644)
	if p := newestGamescopePNG(start.Add(-time.Second)); filepath.Base(p) != "gamescope.png" {
		t.Fatalf("got %q", p)
	}
}

type countingCapturer struct {
	mu    sync.Mutex
	calls int
}

func (c *countingCapturer) Capture(ctx context.Context) (Shot, error) {
	c.mu.Lock()
	c.calls++
	c.mu.Unlock()
	return Shot{Image: solid(40, 20, color.NRGBA{255, 0, 0, 255}), CapturedAt: time.Now(), Backend: "fake"}, nil
}

func TestOnLineWorkerDebouncesAndCaches(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	lines := make(chan int64, 16)
	cache := NewCache(2)
	cap := &countingCapturer{}
	go OnLineWorker(ctx, lines, 50*time.Millisecond, cap, cache, quiet)

	lines <- 1
	time.Sleep(150 * time.Millisecond)
	if !cache.Has(1) {
		t.Fatal("line 1 should have a screenshot")
	}
	// Rapid lines (skipping): only the newest gets captured.
	lines <- 2
	lines <- 3
	lines <- 4
	time.Sleep(150 * time.Millisecond)
	if cache.Has(2) || cache.Has(3) || !cache.Has(4) {
		t.Fatal("only the newest line of a burst should be captured")
	}
	cap.mu.Lock()
	calls := cap.calls
	cap.mu.Unlock()
	if calls != 2 {
		t.Fatalf("captures = %d, want 2", calls)
	}
	c, _ := cache.Get(4)
	img, err := c.Image()
	if err != nil || img.Bounds().Dx() != 40 {
		t.Fatalf("cached image: %v %v", err, img)
	}
	// Capacity 2 evicts the oldest.
	cache.Put(Cached{LineID: 5})
	if cache.Has(1) || !cache.Has(4) || !cache.Has(5) {
		t.Fatal("cache eviction wrong")
	}
}
