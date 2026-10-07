package hook

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestScanFindsGameAndSkipsHelpers(t *testing.T) {
	root := t.TempDir()
	writeProc(t, root, "10", "wineserver", "", "WINEPREFIX=/pfx", "/opt/proton/bin/wineserver")
	writeProc(t, root, "20", "wine-preloader", `Z:\games\sgre_steam.exe`, "WINEPREFIX=/pfx\x00WINEFSYNC=1", "/opt/proton/bin/wine-preloader")
	writeProc(t, root, "21", "wine-preloader", `C:\windows\explorer.exe`, "WINEPREFIX=/pfx", "/opt/proton/bin/wine-preloader")
	writeProc(t, root, "22", "wine-preloader", `Z:\games\vcredist_x64.exe`, "WINEPREFIX=/pfx", "/opt/proton/bin/wine-preloader")
	writeProc(t, root, "23", "python3", "/usr/bin/python3", "HOME=/home/deck", "/usr/bin/python3")

	games, servers := (Scanner{ProcDir: root, UID: -1}).Scan(Filter{})
	if len(servers) != 1 || servers[0].PID != 10 || servers[0].Prefix != "/pfx" {
		t.Fatalf("servers %+v", servers)
	}
	if len(games) != 1 || games[0].Exe != "sgre_steam.exe" || games[0].Prefix != "/pfx" || games[0].Env["WINEFSYNC"] != "1" {
		t.Fatalf("games %+v", games)
	}
	if games[0].StartTick != 4242 {
		t.Fatalf("start tick %d", games[0].StartTick)
	}

	only, _ := (Scanner{ProcDir: root, UID: -1}).Scan(Filter{Allow: []string{"other.exe"}})
	if len(only) != 0 {
		t.Fatalf("allow list should exclude the game, got %+v", only)
	}
	denied, _ := (Scanner{ProcDir: root, UID: -1}).Scan(Filter{Deny: []string{"sgre_steam.exe"}})
	if len(denied) != 0 {
		t.Fatalf("deny list should exclude the game, got %+v", denied)
	}
}

func TestStartTick(t *testing.T) {
	dir := t.TempDir()
	rest := "S " + strings.Repeat("0 ", 18) + "4242\n"
	if err := os.WriteFile(filepath.Join(dir, "stat"), []byte("20 (sgre_steam.exe) "+rest), 0o644); err != nil {
		t.Fatal(err)
	}
	if got := startTick(filepath.Join(dir, "stat")); got != 4242 {
		t.Fatalf("startTick = %d", got)
	}
}

func writeProc(t *testing.T, root, pid, comm, argv0, env, exe string) {
	t.Helper()
	dir := filepath.Join(root, pid)
	if err := os.Mkdir(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	mustWrite(t, filepath.Join(dir, "comm"), comm+"\n")
	mustWrite(t, filepath.Join(dir, "cmdline"), argv0+"\x00")
	mustWrite(t, filepath.Join(dir, "environ"), env)
	rest := "S " + strings.Repeat("0 ", 18) + "4242\n"
	mustWrite(t, filepath.Join(dir, "stat"), pid+" ("+comm+") "+rest)
	if exe != "" {
		if err := os.Symlink(exe, filepath.Join(dir, "exe")); err != nil {
			t.Fatal(err)
		}
	}
}

func mustWrite(t *testing.T, path, data string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(data), 0o644); err != nil {
		t.Fatal(err)
	}
}
