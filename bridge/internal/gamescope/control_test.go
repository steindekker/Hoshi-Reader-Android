package gamescope

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// fakeCompositor speaks just enough Wayland to stand in for gamescope.
type fakeCompositor struct {
	version   uint32 // advertised gamescope_control version; 0 = not advertised
	noEvent   bool   // never send screenshot_taken (simulates a failed capture)
	gotPath   chan string
	gotType   chan uint32
	otherPath string // also broadcast a screenshot_taken for another client's path
}

func (f *fakeCompositor) serve(t *testing.T, l net.Listener) {
	for {
		c, err := l.Accept()
		if err != nil {
			return
		}
		go f.handle(t, c)
	}
}

func writeMsg(c net.Conn, obj, op uint32, args []byte) {
	hdr := make([]byte, 8)
	binary.LittleEndian.PutUint32(hdr, obj)
	binary.LittleEndian.PutUint32(hdr[4:], uint32(8+len(args))<<16|op)
	c.Write(append(hdr, args...))
}

func (f *fakeCompositor) handle(t *testing.T, c net.Conn) {
	defer c.Close()
	for {
		var hdr [8]byte
		if _, err := io.ReadFull(c, hdr[:]); err != nil {
			return
		}
		obj := binary.LittleEndian.Uint32(hdr[:])
		word := binary.LittleEndian.Uint32(hdr[4:])
		body := make([]byte, int(word>>16)-8)
		if _, err := io.ReadFull(c, body); err != nil {
			return
		}
		r := reader{b: body}
		switch op := word & 0xffff; {
		case obj == 1 && op == 1: // get_registry
			r.uint()
			writeMsg(c, 2, 0, append(append(u32(1), str("wl_compositor")...), u32(4)...))
			if f.version > 0 {
				writeMsg(c, 2, 0, append(append(u32(7), str("gamescope_control")...), u32(f.version)...))
			}
		case obj == 1 && op == 0: // sync
			id := r.uint()
			writeMsg(c, id, 0, u32(0))
		case obj == 2 && op == 0: // bind
			name, iface, ver, id := r.uint(), r.string(), r.uint(), r.uint()
			if name != 7 || iface != "gamescope_control" || ver != 3 || id != 4 {
				t.Errorf("bad bind: %d %q v%d id%d", name, iface, ver, id)
			}
			// gamescope announces features on bind; clients must skip them.
			writeMsg(c, 4, 0, append(append(u32(1), u32(1)...), u32(0)...))
		case obj == 4 && op == 2: // take_screenshot
			path, typ, flags := r.string(), r.uint(), r.uint()
			_ = flags
			f.gotPath <- path
			f.gotType <- typ
			if f.noEvent {
				continue
			}
			if f.otherPath != "" {
				writeMsg(c, 4, 2, str(f.otherPath))
			}
			os.WriteFile(path, []byte("fake png"), 0o644)
			writeMsg(c, 4, 2, str(path))
		}
	}
}

func startFake(t *testing.T, f *fakeCompositor) string {
	t.Helper()
	dir := t.TempDir()
	sock := filepath.Join(dir, "gamescope-0")
	l, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	f.gotPath = make(chan string, 4)
	f.gotType = make(chan uint32, 4)
	go f.serve(t, l)
	return sock
}

func TestTakeScreenshot(t *testing.T) {
	f := &fakeCompositor{version: 7, otherPath: "/tmp/someone-else.png"}
	sock := startFake(t, f)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	c, err := Connect(ctx, sock)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	out := filepath.Join(t.TempDir(), "shot.png")
	if err := c.TakeScreenshot(ctx, out, TypeBasePlaneOnly); err != nil {
		t.Fatal(err)
	}
	if p := <-f.gotPath; p != out {
		t.Fatalf("path = %q", p)
	}
	if typ := <-f.gotType; typ != TypeBasePlaneOnly {
		t.Fatalf("type = %d", typ)
	}
	if _, err := os.Stat(out); err != nil {
		t.Fatal("file not written")
	}
}

func TestConnectRejectsOldOrMissingControl(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if _, err := Connect(ctx, startFake(t, &fakeCompositor{version: 2})); err == nil || !strings.Contains(err.Error(), "take_screenshot") {
		t.Fatalf("v2 should be rejected, got %v", err)
	}
	if _, err := Connect(ctx, startFake(t, &fakeCompositor{})); err == nil || !strings.Contains(err.Error(), "not advertised") {
		t.Fatalf("missing interface should be rejected, got %v", err)
	}
}

func TestTakeScreenshotTimesOut(t *testing.T) {
	sock := startFake(t, &fakeCompositor{version: 3, noEvent: true})
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	c, err := Connect(ctx, sock)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	start := time.Now()
	if err := c.TakeScreenshot(ctx, "/nonexistent/x.png", 1); err == nil {
		t.Fatal("expected timeout")
	}
	if time.Since(start) > 2*time.Second {
		t.Fatal("timeout not honoured")
	}
}

func TestWireStrings(t *testing.T) {
	for _, s := range []string{"", "a", "abc", "abcd", "gamescope_control"} {
		b := str(s)
		if len(b)%4 != 0 {
			t.Fatalf("str(%q) not padded", s)
		}
		r := reader{b: b}
		if got := r.string(); got != s || r.err != nil || len(r.b) != 0 {
			t.Fatalf("roundtrip %q -> %q (%v, %d left)", s, got, r.err, len(r.b))
		}
	}
}

func TestSocketPaths(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_RUNTIME_DIR", dir)
	t.Setenv("GAMESCOPE_WAYLAND_DISPLAY", "")
	for _, n := range []string{"gamescope-0", "gamescope-0-ei", "gamescope-1", "wayland-0"} {
		l, err := net.Listen("unix", filepath.Join(dir, n))
		if err != nil {
			t.Fatal(err)
		}
		defer l.Close()
	}
	os.WriteFile(filepath.Join(dir, "gamescope-0.lock"), nil, 0o644)
	got := SocketPaths()
	if len(got) != 2 || filepath.Base(got[0]) != "gamescope-0" || filepath.Base(got[1]) != "gamescope-1" {
		t.Fatalf("SocketPaths = %v", got)
	}
	t.Setenv("GAMESCOPE_WAYLAND_DISPLAY", "gamescope-1")
	if got := SocketPaths(); filepath.Base(got[0]) != "gamescope-1" || len(got) != 2 {
		t.Fatalf("env socket should come first: %v", got)
	}
}
