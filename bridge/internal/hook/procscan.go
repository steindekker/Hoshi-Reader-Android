package hook

import (
	"bytes"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

// GameProcess is a Windows program running under Wine/Proton that looks
// like a game (not a Wine/Steam helper).
type GameProcess struct {
	PID       int
	Exe       string            // executable name, e.g. "sgre_steam.exe"
	WinPath   string            // command line argv[0], e.g. `Z:\home\deck\Games\X\x.exe`
	Prefix    string            // WINEPREFIX (no trailing slash)
	Env       map[string]string // the process environment
	Loader    string            // /proc/<pid>/exe target (Wine preloader), may be empty
	StartTick uint64            // start time in clock ticks since boot (stat field 22)
}

// Wineserver is a running wineserver and the prefix it serves.
type Wineserver struct {
	PID    int
	Exe    string
	Prefix string
}

// builtinDeny are Windows executables that are never games: Wine and Proton
// services, Steam's helpers, installers and crash handlers.
var builtinDeny = []string{
	"steam.exe", "explorer.exe", "services.exe", "winedevice.exe", "plugplay.exe",
	"svchost.exe", "rpcss.exe", "tabtip.exe", "xalia.exe", "conhost.exe", "start.exe",
	"wineboot.exe", "winemenubuilder.exe", "rundll32.exe", "cmd.exe", "wineconsole.exe",
	"msiexec.exe", "regedit.exe", "reg.exe", "regsvr32.exe", "notepad.exe", "winecfg.exe",
	"control.exe", "taskmgr.exe", "tasklist.exe", "iexplore.exe", "mscorsvw.exe", "ngen.exe",
	"powershell.exe", "pwsh.exe", "wmic.exe", "dxsetup.exe", "dotnetfx35.exe",
	"steamerrorreporter.exe", "steamerrorreporter64.exe", "steamservice.exe",
	"unitycrashhandler32.exe", "unitycrashhandler64.exe", "crashreportclient.exe",
	"easyanticheat.exe", "easyanticheat_eos.exe", "beservice.exe", "beservice_x64.exe",
	"vnhook.exe", "vnhook32.exe",
}

// denyPrefixes reject installers and redistributables by name.
var denyPrefixes = []string{"vcredist", "vc_redist", "dotnetfx", "ndp4", "directx", "dxwebsetup", "oalinst", "physx", "uninst", "unins0"}

// Filter decides which Windows executables are hooked.
type Filter struct {
	Allow []string // if non-empty, only these names (case-insensitive, * and ? globs) are hooked
	Deny  []string // never hooked, in addition to the built-in list
}

// Accepts reports whether a process with the given executable name and
// Windows path may be hooked.
func (f Filter) Accepts(exe, winPath string) bool {
	name := strings.ToLower(exe)
	if !strings.HasSuffix(name, ".exe") {
		return false
	}
	if len(f.Allow) > 0 {
		return matchAny(f.Allow, name)
	}
	if matchAny(f.Deny, name) {
		return false
	}
	for _, d := range builtinDeny {
		if name == d {
			return false
		}
	}
	for _, p := range denyPrefixes {
		if strings.HasPrefix(name, p) {
			return false
		}
	}
	lp := strings.ToLower(strings.ReplaceAll(winPath, "/", `\`))
	if strings.Contains(lp, `:\windows\`) || strings.Contains(lp, `\steamapps\common\proton`) ||
		strings.Contains(lp, `\compatibilitytools.d\`) || strings.Contains(lp, `\steam-runtime`) {
		return false
	}
	return true
}

func matchAny(patterns []string, name string) bool {
	for _, p := range patterns {
		if ok, _ := path.Match(strings.ToLower(p), name); ok {
			return true
		}
	}
	return false
}

// Scanner reads /proc.
type Scanner struct {
	ProcDir string // default /proc
	UID     int    // only processes of this user; < 0 = any
}

// Scan returns the game processes and the wineservers that are running.
func (s Scanner) Scan(f Filter) ([]GameProcess, []Wineserver) {
	dir := s.ProcDir
	if dir == "" {
		dir = "/proc"
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, nil
	}
	var games []GameProcess
	var servers []Wineserver
	for _, e := range entries {
		pid, err := strconv.Atoi(e.Name())
		if err != nil {
			continue
		}
		pdir := filepath.Join(dir, e.Name())
		if s.UID >= 0 && !ownedBy(pdir, s.UID) {
			continue
		}
		comm := readTrim(filepath.Join(pdir, "comm"))
		if comm == "wineserver" {
			env := readEnviron(filepath.Join(pdir, "environ"))
			exe, _ := os.Readlink(filepath.Join(pdir, "exe"))
			servers = append(servers, Wineserver{PID: pid, Exe: exe, Prefix: prefixOf(env)})
			continue
		}
		argv0 := readArgv0(filepath.Join(pdir, "cmdline"))
		if !looksLikeWindowsExe(argv0) {
			continue
		}
		exe := windowsBase(argv0)
		if !f.Accepts(exe, argv0) {
			continue
		}
		env := readEnviron(filepath.Join(pdir, "environ"))
		prefix := prefixOf(env)
		if prefix == "" {
			continue // not a Wine process we can join
		}
		loader, _ := os.Readlink(filepath.Join(pdir, "exe"))
		games = append(games, GameProcess{
			PID: pid, Exe: exe, WinPath: argv0, Prefix: prefix, Env: env,
			Loader: loader, StartTick: startTick(filepath.Join(pdir, "stat")),
		})
	}
	sort.Slice(games, func(i, j int) bool { return games[i].StartTick < games[j].StartTick })
	return games, servers
}

// prefixOf returns the Wine prefix from a process environment.
func prefixOf(env map[string]string) string {
	if p := env["WINEPREFIX"]; p != "" {
		return filepath.Clean(p)
	}
	if p := env["STEAM_COMPAT_DATA_PATH"]; p != "" {
		return filepath.Join(filepath.Clean(p), "pfx")
	}
	return ""
}

func looksLikeWindowsExe(argv0 string) bool {
	l := strings.ToLower(argv0)
	if !strings.HasSuffix(l, ".exe") {
		return false
	}
	// Wine rewrites the command line of Windows processes to Windows form.
	return strings.Contains(argv0, `\`) || (len(argv0) > 2 && argv0[1] == ':') || !strings.Contains(argv0, "/")
}

// windowsBase is the file name of a Windows or Unix path.
func windowsBase(p string) string {
	if i := strings.LastIndexAny(p, `\/`); i >= 0 {
		return p[i+1:]
	}
	return p
}

func readTrim(p string) string {
	b, err := os.ReadFile(p)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

func readArgv0(p string) string {
	b, err := os.ReadFile(p)
	if err != nil || len(b) == 0 {
		return ""
	}
	if i := bytes.IndexByte(b, 0); i >= 0 {
		b = b[:i]
	}
	return string(b)
}

func readEnviron(p string) map[string]string {
	b, err := os.ReadFile(p)
	if err != nil {
		return map[string]string{}
	}
	return ParseEnviron(b)
}

// ParseEnviron parses NUL-separated KEY=VALUE pairs.
func ParseEnviron(b []byte) map[string]string {
	env := map[string]string{}
	for _, kv := range bytes.Split(b, []byte{0}) {
		k, v, ok := bytes.Cut(kv, []byte{'='})
		if ok && len(k) > 0 {
			env[string(k)] = string(v)
		}
	}
	return env
}

func ownedBy(pdir string, uid int) bool {
	b, err := os.ReadFile(filepath.Join(pdir, "status"))
	if err != nil {
		return false
	}
	for _, line := range strings.Split(string(b), "\n") {
		if rest, ok := strings.CutPrefix(line, "Uid:"); ok {
			f := strings.Fields(rest)
			return len(f) > 0 && f[0] == strconv.Itoa(uid)
		}
	}
	return false
}

// startTick parses field 22 of /proc/<pid>/stat (after the "(comm)" part,
// which may contain spaces).
func startTick(p string) uint64 {
	b, err := os.ReadFile(p)
	if err != nil {
		return 0
	}
	i := bytes.LastIndexByte(b, ')')
	if i < 0 {
		return 0
	}
	f := strings.Fields(string(b[i+1:]))
	// f[0] is field 3 (state); field 22 is f[19].
	if len(f) < 20 {
		return 0
	}
	n, _ := strconv.ParseUint(f[19], 10, 64)
	return n
}
