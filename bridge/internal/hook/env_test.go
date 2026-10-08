package hook

import (
	"strings"
	"testing"
)

type mapFS struct {
	files map[string]bool
	dir   string
}

func (m mapFS) Exists(p string) bool             { return m.files[p] }
func (m mapFS) ServerDir(string) (string, error) { return m.dir, nil }

func TestPrepareLaunchJoinsPrefix(t *testing.T) {
	dist := "/opt/proton/files"
	socketDir := "/tmp/.wine-1/server-ab"
	files := map[string]bool{
		dist + "/bin/wineserver":       true,
		dist + "/bin/wine":             true,
		dist + "/bin/wine64":           true,
		dist + "/lib/x86_64-linux-gnu": true,
		socketDir + "/socket":          true,
	}
	g := GameProcess{
		PID: 20, Exe: "sgre_steam.exe", Prefix: "/pfx",
		Loader: dist + "/bin/wine-preloader",
		Env: map[string]string{
			"WINEPREFIX": "/pfx",
			"WINEFSYNC":  "1",
			"WINESERVER": "/somewhere/else/bin/wineserver",
			"HOME":       "/home/deck",
		},
	}
	launch, err := PrepareLaunch(g, nil, map[string]string{"HOME": "/home/deck", "USER": "deck"}, mapFS{files: files, dir: socketDir})
	if err != nil {
		t.Fatal(err)
	}
	if launch.Wine != dist+"/bin/wine" {
		t.Fatalf("wine binary %s", launch.Wine)
	}
	if launch.Socket != socketDir+"/socket" {
		t.Fatalf("socket %s", launch.Socket)
	}
	env := strings.Join(launch.Env, "\n")
	for _, want := range []string{"WINEPREFIX=/pfx", "WINEFSYNC=1", "WINEDEBUG=-all", "HOME=/home/deck", "USER=deck"} {
		if !strings.Contains(env, want) {
			t.Errorf("env missing %s:\n%s", want, env)
		}
	}
	if strings.Contains(env, "WINESERVER=/somewhere/else") {
		t.Fatalf("game WINESERVER leaked into the helper env:\n%s", env)
	}

	delete(files, socketDir+"/socket")
	if _, err := PrepareLaunch(g, nil, nil, mapFS{files: files, dir: socketDir}); err == nil {
		t.Fatal("missing wineserver socket should fail rather than start a second server")
	}
}
