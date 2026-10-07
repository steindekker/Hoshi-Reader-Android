package hook

import (
	"archive/zip"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

// LunaHook release pinned for the Deck. The DLLs are downloaded on install
// (and again if a file is missing or its hash does not match); they are not
// committed to this repo.
const (
	LunaVersion = "v12.0.0"
	LunaZipURL  = "https://github.com/HIllya51/LunaTranslator/releases/download/v12.0.0/LunaTranslator_x64.zip"
)

// Dist is a pinned set of files inside a zip.
type Dist struct {
	URL   string
	Files []DistFile
}

// DistFile is one file extracted from Dist.URL.
type DistFile struct {
	Name    string // file name written into the destination directory
	ZipPath string // path inside the zip
	SHA256  string // lowercase hex
}

// LunaDist is the LunaHook build vnhook.exe loads.
var LunaDist = Dist{
	URL: LunaZipURL,
	Files: []DistFile{
		{Name: "LunaHook64.dll", ZipPath: "LunaTranslator_x64/files/LunaHook/LunaHook64.dll", SHA256: "ff7ac5b4989a3f4c46156e1ff65df70cef561835b4c634756f0b92363f1b0c8d"},
		{Name: "LunaHook32.dll", ZipPath: "LunaTranslator_x64/files/LunaHook/LunaHook32.dll", SHA256: "f33fd19b5afc1349906dbdadefe9cbc598aea5de3645a082f605b229da37b849"},
		{Name: "LunaHost64.dll", ZipPath: "LunaTranslator_x64/files/LunaHook/LunaHost64.dll", SHA256: "62cd03c13a6df405577872e0b2201f1b709d0e6ce1089c80de99a074aad80ba9"},
	},
}

// Ensure makes dir contain every file of d, downloading the zip when any
// file is missing or has the wrong hash. Files that already match are left
// untouched, so a retry does not re-download.
func (d Dist) Ensure(dir string) error {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	var missing []DistFile
	for _, f := range d.Files {
		if err := verifyFile(filepath.Join(dir, f.Name), f.SHA256); err != nil {
			missing = append(missing, f)
		}
	}
	if len(missing) == 0 {
		return nil
	}
	if d.URL == "" {
		return fmt.Errorf("lunahook: %s is missing and no download URL is configured", missing[0].Name)
	}
	zpath, err := download(d.URL)
	if err != nil {
		return err
	}
	defer os.Remove(zpath)
	zr, err := zip.OpenReader(zpath)
	if err != nil {
		return fmt.Errorf("open lunahook zip: %w", err)
	}
	defer zr.Close()
	byName := map[string]*zip.File{}
	for _, f := range zr.File {
		byName[f.Name] = f
	}
	for _, want := range missing {
		zf := byName[want.ZipPath]
		if zf == nil {
			return fmt.Errorf("lunahook zip has no %s", want.ZipPath)
		}
		if err := extractChecked(zf, filepath.Join(dir, want.Name), want.SHA256); err != nil {
			return err
		}
	}
	return nil
}

func verifyFile(path, sum string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return err
	}
	got := hex.EncodeToString(h.Sum(nil))
	if got != sum {
		return fmt.Errorf("%s: hash %s, want %s", filepath.Base(path), got, sum)
	}
	return nil
}

func download(url string) (string, error) {
	f, err := os.CreateTemp("", "lunahook-*.zip")
	if err != nil {
		return "", err
	}
	path := f.Name()
	cleanup := func() {
		f.Close()
		os.Remove(path)
	}
	client := &http.Client{Timeout: 5 * time.Minute}
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		cleanup()
		return "", err
	}
	req.Header.Set("User-Agent", "hoshi-bridge")
	resp, err := client.Do(req)
	if err != nil {
		cleanup()
		return "", fmt.Errorf("download lunahook: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		cleanup()
		return "", fmt.Errorf("download lunahook: %s", resp.Status)
	}
	if _, err := io.Copy(f, resp.Body); err != nil {
		cleanup()
		return "", fmt.Errorf("download lunahook: %w", err)
	}
	if err := f.Close(); err != nil {
		os.Remove(path)
		return "", err
	}
	return path, nil
}

func extractChecked(zf *zip.File, dest, sum string) error {
	r, err := zf.Open()
	if err != nil {
		return err
	}
	defer r.Close()
	tmp := dest + ".tmp"
	out, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	h := sha256.New()
	if _, err := io.Copy(out, io.TeeReader(r, h)); err != nil {
		out.Close()
		os.Remove(tmp)
		return err
	}
	if err := out.Close(); err != nil {
		os.Remove(tmp)
		return err
	}
	got := hex.EncodeToString(h.Sum(nil))
	if got != sum {
		os.Remove(tmp)
		return fmt.Errorf("%s: downloaded hash %s, want %s", filepath.Base(dest), got, sum)
	}
	return os.Rename(tmp, dest)
}
