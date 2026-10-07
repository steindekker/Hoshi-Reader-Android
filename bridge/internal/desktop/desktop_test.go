package desktop

import (
	"bytes"
	"encoding/binary"
	"net"
	"os"
	"path/filepath"
	"testing"
)

func xauthRecord(family uint16, addr, disp, name string, data []byte) []byte {
	var b bytes.Buffer
	binary.Write(&b, binary.BigEndian, family)
	for _, s := range [][]byte{[]byte(addr), []byte(disp), []byte(name), data} {
		binary.Write(&b, binary.BigEndian, uint16(len(s)))
		b.Write(s)
	}
	return b.Bytes()
}

func TestXAuthCookie(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_RUNTIME_DIR", dir)
	t.Setenv("HOME", dir)
	host, _ := os.Hostname()
	cookie := bytes.Repeat([]byte{0xab}, 16)
	data := append(xauthRecord(256, host, "1", "MIT-MAGIC-COOKIE-1", bytes.Repeat([]byte{1}, 16)),
		xauthRecord(256, host, "0", "MIT-MAGIC-COOKIE-1", cookie)...)
	os.WriteFile(filepath.Join(dir, "xauth_AbCdEf"), data, 0o600)
	t.Setenv("XAUTHORITY", "")
	if got := XAuthCookie(":0"); got != "abababababababababababababababab" {
		t.Fatalf("cookie for :0 = %q", got)
	}
	if got := XAuthCookie(":1"); got != "01010101010101010101010101010101" {
		t.Fatalf("cookie for :1 = %q", got)
	}
	if got := XAuthCookie(":7"); got != "" {
		t.Fatalf("no cookie expected for :7, got %q", got)
	}
}

func TestDisplayHelpers(t *testing.T) {
	for in, want := range map[string]int{":0": 0, ":1.0": 1, "unix:12": 12} {
		if n, ok := DisplayNumber(in); !ok || n != want {
			t.Errorf("DisplayNumber(%q) = %d,%v", in, n, ok)
		}
	}
	if _, ok := DisplayNumber("nope"); ok {
		t.Error("expected failure")
	}
	dir := t.TempDir()
	old := X11SocketDir
	X11SocketDir = dir
	defer func() { X11SocketDir = old }()
	for _, n := range []string{"X10", "X1", "X0", "junk"} {
		os.WriteFile(filepath.Join(dir, n), nil, 0o644)
	}
	got := X11Displays()
	if len(got) != 3 || got[0] != ":0" || got[1] != ":1" || got[2] != ":10" {
		t.Fatalf("X11Displays = %v", got)
	}
}

func TestWaylandSocketsAndProcess(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_RUNTIME_DIR", dir)
	l, err := net.Listen("unix", filepath.Join(dir, "wayland-0"))
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	os.WriteFile(filepath.Join(dir, "wayland-0.lock"), nil, 0o644)
	os.WriteFile(filepath.Join(dir, "wayland-9"), nil, 0o644) // not a socket
	if got := WaylandSockets("wayland-"); len(got) != 1 || got[0] != "wayland-0" {
		t.Fatalf("WaylandSockets = %v", got)
	}

	proc := t.TempDir()
	old := ProcDir
	ProcDir = proc
	defer func() { ProcDir = old }()
	os.MkdirAll(filepath.Join(proc, "123"), 0o755)
	os.WriteFile(filepath.Join(proc, "123", "comm"), []byte("kwin_wayland\n"), 0o644)
	if !ProcessRunning("gamescope", "kwin_wayland") || ProcessRunning("gamescope") {
		t.Fatal("ProcessRunning wrong")
	}
}

func TestMergeEnv(t *testing.T) {
	t.Setenv("VNX_TEST_A", "1")
	env := MergeEnv(map[string]string{"VNX_TEST_A": "2", "VNX_TEST_B": "x"}, map[string]string{"VNX_TEST_B": "y"})
	m := map[string]string{}
	for _, kv := range env {
		k, v, _ := bytes.Cut([]byte(kv), []byte("="))
		m[string(k)] = string(v)
	}
	if m["VNX_TEST_A"] != "2" || m["VNX_TEST_B"] != "y" {
		t.Fatalf("MergeEnv: A=%q B=%q", m["VNX_TEST_A"], m["VNX_TEST_B"])
	}
}
