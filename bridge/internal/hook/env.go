package hook

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"syscall"
)

// Launch is how to start a Windows program inside a running game's prefix
// so that it joins the game's wineserver (the protontricks-launch approach:
// same Wine build, same prefix, same sync mode).
type Launch struct {
	Wine   string   // wine (or wine64) binary of the game's Wine/Proton build
	Dist   string   // Wine dist dir (contains bin/wineserver)
	Prefix string   // WINEPREFIX
	Env    []string // complete environment for the child
	Socket string   // the wineserver socket the child will connect to
}

// FS abstracts the few file checks PrepareLaunch needs (tests fake it).
type FS interface {
	Exists(path string) bool
	// ServerDir returns /tmp/.wine-<uid>/server-<dev>-<ino> for a prefix.
	ServerDir(prefix string) (string, error)
}

// OSFS is the real file system.
type OSFS struct{}

func (OSFS) Exists(p string) bool { _, err := os.Stat(p); return err == nil }

// ServerDir mirrors Wine's server_dir: the prefix's device and inode in hex.
func (OSFS) ServerDir(prefix string) (string, error) {
	var st syscall.Stat_t
	if err := syscall.Stat(prefix, &st); err != nil {
		return "", err
	}
	return fmt.Sprintf("/tmp/.wine-%d/server-%x-%x", os.Getuid(), uint64(st.Dev), st.Ino), nil
}

// envCopy are the variables taken over from the game process: they select
// the prefix, the DLL search path/overrides and the synchronisation
// primitives (esync/fsync/ntsync), which must match the running wineserver.
func envCopy(k string) bool {
	switch k {
	case "WINESERVERSOCKET", // an inherited fd number, meaningless for us
		"WINELOADERNOEXEC", "WINEPRELOADRESERVE", // per-process loader state
		"WINE_LD_PRELOAD", "WINEDEBUG", "WINELOADER", "WINESERVER":
		return false
	}
	return strings.HasPrefix(k, "WINE") || strings.HasPrefix(k, "PROTON_") || k == "STEAM_COMPAT_DATA_PATH"
}

// envKeep are host variables passed through from the daemon.
var envKeep = []string{"HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "XDG_RUNTIME_DIR", "TZ"}

// FindDist locates the Wine build (the directory containing bin/wineserver)
// the game runs with: from the game's loader path, $WINESERVER, the
// prefix's wineserver process, or Steam's compat tool path.
func FindDist(g GameProcess, servers []Wineserver, fs FS) (string, error) {
	var cands []string
	if g.Loader != "" {
		for d := filepath.Dir(g.Loader); d != "/" && d != "." && d != ""; d = filepath.Dir(d) {
			cands = append(cands, d)
		}
	}
	if ws := g.Env["WINESERVER"]; ws != "" {
		cands = append(cands, filepath.Dir(filepath.Dir(ws)))
	}
	for _, s := range servers {
		if s.Prefix == g.Prefix && s.Exe != "" {
			cands = append(cands, filepath.Dir(filepath.Dir(strings.TrimSuffix(s.Exe, " (deleted)"))))
		}
	}
	if tools := g.Env["STEAM_COMPAT_TOOL_PATHS"]; tools != "" {
		for _, t := range strings.Split(tools, ":") {
			cands = append(cands, filepath.Join(t, "files"), filepath.Join(t, "dist"))
		}
	}
	for _, d := range cands {
		if fs.Exists(filepath.Join(d, "bin", "wineserver")) &&
			(fs.Exists(filepath.Join(d, "bin", "wine")) || fs.Exists(filepath.Join(d, "bin", "wine64"))) {
			return d, nil
		}
	}
	return "", errors.New("cannot find the Wine/Proton build of the game (no bin/wineserver next to its loader)")
}

// PrepareLaunch builds the command environment for running a 64-bit
// Windows helper in the game's prefix.
func PrepareLaunch(g GameProcess, servers []Wineserver, host map[string]string, fs FS) (Launch, error) {
	if g.Prefix == "" {
		return Launch{}, errors.New("game has no WINEPREFIX")
	}
	dist, err := FindDist(g, servers, fs)
	if err != nil {
		return Launch{}, err
	}
	sdir, err := fs.ServerDir(g.Prefix)
	if err != nil {
		return Launch{}, fmt.Errorf("prefix %s: %w", g.Prefix, err)
	}
	socket := filepath.Join(sdir, "socket")
	if !fs.Exists(socket) {
		// Starting Wine now would spawn a second wineserver for the same
		// prefix (e.g. the game runs in a sandbox with a private /tmp).
		return Launch{}, fmt.Errorf("the game's wineserver socket %s is not visible", socket)
	}
	// Prefer the wine launcher script. On Proton that script is what joins an
	// already-running prefix; wine64 alone can miss the runtime the script sets.
	wine := filepath.Join(dist, "bin", "wine")
	if !fs.Exists(wine) {
		wine = filepath.Join(dist, "bin", "wine64")
	}
	env := map[string]string{}
	for _, k := range envKeep {
		if v, ok := host[k]; ok && v != "" {
			env[k] = v
		} else if v, ok := g.Env[k]; ok && v != "" {
			env[k] = v
		}
	}
	for k, v := range g.Env {
		if envCopy(k) {
			env[k] = v
		}
	}
	env["WINEPREFIX"] = g.Prefix
	env["WINEDEBUG"] = "-all"
	env["WINESERVER"] = filepath.Join(dist, "bin", "wineserver")
	env["WINELOADER"] = wine
	// No display: vnhook is a console program and must never open a window.
	env["PATH"] = filepath.Join(dist, "bin") + ":/usr/bin:/bin"
	var libs []string
	for _, l := range []string{"lib64", "lib", "lib/x86_64-linux-gnu", "lib/i386-linux-gnu"} {
		if p := filepath.Join(dist, l); fs.Exists(p) {
			libs = append(libs, p)
		}
	}
	if len(libs) > 0 {
		env["LD_LIBRARY_PATH"] = strings.Join(libs, ":")
	}
	if _, ok := env["WINEDLLPATH"]; !ok {
		var dll []string
		for _, l := range []string{"lib/vkd3d", "lib/wine", "lib64/wine"} {
			if p := filepath.Join(dist, l); fs.Exists(p) {
				dll = append(dll, p)
			}
		}
		if len(dll) > 0 {
			env["WINEDLLPATH"] = strings.Join(dll, ":")
		}
	}
	keys := make([]string, 0, len(env))
	for k := range env {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	out := make([]string, 0, len(keys))
	for _, k := range keys {
		out = append(out, k+"="+env[k])
	}
	return Launch{Wine: wine, Dist: dist, Prefix: g.Prefix, Env: out, Socket: socket}, nil
}

// HostEnv returns the daemon's environment as a map.
func HostEnv() map[string]string {
	m := map[string]string{}
	for _, kv := range os.Environ() {
		if k, v, ok := strings.Cut(kv, "="); ok {
			m[k] = v
		}
	}
	return m
}
