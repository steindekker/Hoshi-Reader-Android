// Package desktop discovers the graphical session (gamescope Game Mode,
// KDE Desktop Mode, or any other compositor) from a process that was not
// started inside it, e.g. a systemd --user service whose environment lacks
// DISPLAY / WAYLAND_DISPLAY.
package desktop

import (
	"bytes"
	"encoding/binary"
	"encoding/hex"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

// X11SocketDir is where X servers (including Xwayland) create their sockets.
// It is a variable so tests can point it elsewhere.
var X11SocketDir = "/tmp/.X11-unix"

// ProcDir is the procfs root (tests may override it).
var ProcDir = "/proc"

// RuntimeDir returns $XDG_RUNTIME_DIR or /run/user/<uid>.
func RuntimeDir() string {
	if d := os.Getenv("XDG_RUNTIME_DIR"); d != "" {
		return d
	}
	return "/run/user/" + strconv.Itoa(os.Getuid())
}

// X11Displays lists displays (":0", ":1", ...) that have a socket, ascending.
func X11Displays() []string {
	entries, err := os.ReadDir(X11SocketDir)
	if err != nil {
		return nil
	}
	var nums []int
	for _, e := range entries {
		name := e.Name()
		if !strings.HasPrefix(name, "X") {
			continue
		}
		n, err := strconv.Atoi(name[1:])
		if err == nil {
			nums = append(nums, n)
		}
	}
	sort.Ints(nums)
	out := make([]string, len(nums))
	for i, n := range nums {
		out[i] = ":" + strconv.Itoa(n)
	}
	return out
}

// DisplayNumber parses ":0", ":0.0", "unix:1" into the display number.
func DisplayNumber(display string) (int, bool) {
	i := strings.LastIndexByte(display, ':')
	if i < 0 {
		return 0, false
	}
	num := display[i+1:]
	if j := strings.IndexByte(num, '.'); j >= 0 {
		num = num[:j]
	}
	n, err := strconv.Atoi(num)
	return n, err == nil
}

// X11SocketPath is the unix socket of a local display.
func X11SocketPath(display string) (string, bool) {
	n, ok := DisplayNumber(display)
	if !ok {
		return "", false
	}
	return filepath.Join(X11SocketDir, "X"+strconv.Itoa(n)), true
}

// WaylandSockets lists sockets in the runtime dir whose name starts with
// prefix (e.g. "wayland-" or "gamescope-"), ignoring lock files.
func WaylandSockets(prefix string) []string {
	dir := RuntimeDir()
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var out []string
	for _, e := range entries {
		name := e.Name()
		if !strings.HasPrefix(name, prefix) || strings.HasSuffix(name, ".lock") {
			continue
		}
		if e.Type()&os.ModeSocket == 0 {
			continue
		}
		out = append(out, name)
	}
	sort.Strings(out)
	return out
}

// ProcessRunning reports whether a process owned by anyone has one of the
// given command names (as in /proc/<pid>/comm, truncated to 15 bytes).
func ProcessRunning(names ...string) bool {
	entries, err := os.ReadDir(ProcDir)
	if err != nil {
		return false
	}
	want := map[string]bool{}
	for _, n := range names {
		if len(n) > 15 {
			n = n[:15]
		}
		want[n] = true
	}
	for _, e := range entries {
		if e.Name()[0] < '0' || e.Name()[0] > '9' {
			continue
		}
		b, err := os.ReadFile(filepath.Join(ProcDir, e.Name(), "comm"))
		if err != nil {
			continue
		}
		if want[strings.TrimSpace(string(b))] {
			return true
		}
	}
	return false
}

// XAuthCookie finds an MIT-MAGIC-COOKIE-1 for the display, returned as hex.
// It checks $XAUTHORITY, KDE/SDDM-style $XDG_RUNTIME_DIR/xauth_* files and
// ~/.Xauthority. An empty result means "try without authentication", which
// is what gamescope's Xwayland expects.
func XAuthCookie(display string) string {
	num, ok := DisplayNumber(display)
	if !ok {
		return ""
	}
	var files []string
	if f := os.Getenv("XAUTHORITY"); f != "" {
		files = append(files, f)
	}
	matches, _ := filepath.Glob(filepath.Join(RuntimeDir(), "xauth_*"))
	sort.Slice(matches, func(i, j int) bool { return modTime(matches[i]) > modTime(matches[j]) })
	files = append(files, matches...)
	if home, err := os.UserHomeDir(); err == nil {
		files = append(files, filepath.Join(home, ".Xauthority"))
	}
	host, _ := os.Hostname()
	for _, f := range files {
		if c := cookieFromFile(f, host, strconv.Itoa(num)); c != "" {
			return c
		}
	}
	return ""
}

func modTime(p string) int64 {
	st, err := os.Stat(p)
	if err != nil {
		return 0
	}
	return st.ModTime().UnixNano()
}

// cookieFromFile parses the Xauthority format (see Xauth.h): repeated
// records of family(u16) + 4 length-prefixed big-endian strings.
func cookieFromFile(path, host, display string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	r := bytes.NewReader(data)
	readStr := func() ([]byte, error) {
		var n uint16
		if err := binary.Read(r, binary.BigEndian, &n); err != nil {
			return nil, err
		}
		b := make([]byte, n)
		_, err := io.ReadFull(r, b)
		return b, err
	}
	const familyLocal, familyWild = 256, 65535
	for {
		var family uint16
		if err := binary.Read(r, binary.BigEndian, &family); err != nil {
			return ""
		}
		addr, err1 := readStr()
		disp, err2 := readStr()
		name, err3 := readStr()
		cookie, err4 := readStr()
		if err1 != nil || err2 != nil || err3 != nil || err4 != nil {
			return ""
		}
		addrOK := family == familyWild || (family == familyLocal && (string(addr) == host || host == ""))
		dispOK := len(disp) == 0 || string(disp) == display
		if addrOK && dispOK && string(name) == "MIT-MAGIC-COOKIE-1" && len(cookie) == 16 {
			return hex.EncodeToString(cookie)
		}
	}
}

// LookPath is like exec.LookPath but also searches common user bin dirs
// that a systemd --user service's PATH may lack.
func LookPath(name string) (string, bool) {
	if strings.ContainsRune(name, '/') {
		st, err := os.Stat(name)
		return name, err == nil && !st.IsDir() && st.Mode()&0o111 != 0
	}
	dirs := filepath.SplitList(os.Getenv("PATH"))
	if home, err := os.UserHomeDir(); err == nil {
		dirs = append(dirs, filepath.Join(home, ".local", "bin"))
	}
	dirs = append(dirs, "/usr/local/bin", "/usr/bin", "/bin")
	for _, d := range dirs {
		p := filepath.Join(d, name)
		if st, err := os.Stat(p); err == nil && !st.IsDir() && st.Mode()&0o111 != 0 {
			return p, true
		}
	}
	return "", false
}

// SessionEnv returns environment overrides that let child processes reach
// the current graphical session: XDG_RUNTIME_DIR, WAYLAND_DISPLAY (first
// non-gamescope compositor socket), DISPLAY and XAUTHORITY, each only when
// not already set in our own environment.
func SessionEnv() map[string]string {
	env := map[string]string{}
	if os.Getenv("XDG_RUNTIME_DIR") == "" {
		env["XDG_RUNTIME_DIR"] = RuntimeDir()
	}
	if os.Getenv("WAYLAND_DISPLAY") == "" {
		if s := WaylandSockets("wayland-"); len(s) > 0 {
			env["WAYLAND_DISPLAY"] = s[0]
		}
	}
	if os.Getenv("DISPLAY") == "" {
		if d := X11Displays(); len(d) > 0 {
			env["DISPLAY"] = d[0]
		}
	}
	if os.Getenv("XAUTHORITY") == "" {
		matches, _ := filepath.Glob(filepath.Join(RuntimeDir(), "xauth_*"))
		if len(matches) > 0 {
			sort.Slice(matches, func(i, j int) bool { return modTime(matches[i]) > modTime(matches[j]) })
			env["XAUTHORITY"] = matches[0]
		}
	}
	return env
}

// MergeEnv applies overrides (later maps win) on top of os.Environ().
func MergeEnv(overrides ...map[string]string) []string {
	m := map[string]string{}
	var order []string
	for _, kv := range os.Environ() {
		k, v, _ := strings.Cut(kv, "=")
		if _, seen := m[k]; !seen {
			order = append(order, k)
		}
		m[k] = v
	}
	for _, o := range overrides {
		for k, v := range o {
			if _, seen := m[k]; !seen {
				order = append(order, k)
			}
			m[k] = v
		}
	}
	out := make([]string, 0, len(order))
	for _, k := range order {
		out = append(out, k+"="+m[k])
	}
	return out
}
