package hook

import (
	"archive/zip"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestEnsureDownloadsMissingFiles(t *testing.T) {
	body := []byte("lunahook-bytes")
	sum := sha256.Sum256(body)
	hash := hex.EncodeToString(sum[:])
	zipPath := filepath.Join(t.TempDir(), "luna.zip")
	zf, err := os.Create(zipPath)
	if err != nil {
		t.Fatal(err)
	}
	w := zip.NewWriter(zf)
	fw, err := w.Create("LunaTranslator_x64/files/LunaHook/LunaHook64.dll")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := fw.Write(body); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil || zf.Close() != nil {
		t.Fatal(err)
	}
	var hits int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits++
		http.ServeFile(w, r, zipPath)
	}))
	defer srv.Close()

	dist := Dist{URL: srv.URL, Files: []DistFile{{
		Name: "LunaHook64.dll", ZipPath: "LunaTranslator_x64/files/LunaHook/LunaHook64.dll", SHA256: hash,
	}}}
	dir := t.TempDir()
	if err := dist.Ensure(dir); err != nil {
		t.Fatal(err)
	}
	if hits != 1 {
		t.Fatalf("downloads %d", hits)
	}
	if err := dist.Ensure(dir); err != nil {
		t.Fatal(err)
	}
	if hits != 1 {
		t.Fatalf("second Ensure re-downloaded (%d hits)", hits)
	}
	if err := os.WriteFile(filepath.Join(dir, "LunaHook64.dll"), []byte("tampered"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := dist.Ensure(dir); err != nil {
		t.Fatal(err)
	}
	if hits != 2 {
		t.Fatalf("tampered file should be replaced, hits %d", hits)
	}
}

func TestLunaDistIsPinned(t *testing.T) {
	if LunaDist.URL == "" || len(LunaDist.Files) != 3 {
		t.Fatalf("%+v", LunaDist)
	}
	seen := map[string]bool{}
	for _, f := range LunaDist.Files {
		if len(f.SHA256) != 64 || f.ZipPath == "" || f.Name == "" || seen[f.Name] {
			t.Fatalf("bad pin %+v", f)
		}
		seen[f.Name] = true
	}
}
