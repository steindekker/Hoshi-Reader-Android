package media

import (
	"bytes"
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func needFFmpeg(t *testing.T) {
	t.Helper()
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}
}

// testVideo writes an 8 s 1280x720 video with a sine audio track.
func testVideo(t *testing.T) string {
	t.Helper()
	needFFmpeg(t)
	p := filepath.Join(t.TempDir(), "test video.mkv")
	cmd := exec.Command("ffmpeg", "-hide_banner", "-v", "error", "-y",
		"-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=24:duration=8",
		"-f", "lavfi", "-i", "sine=frequency=440:duration=8",
		"-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", p)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Skipf("cannot generate test video: %v %s", err, out)
	}
	return p
}

func TestNeedsMPV(t *testing.T) {
	f := filepath.Join(t.TempDir(), "a.mkv")
	os.WriteFile(f, []byte("x"), 0o644)
	for path, want := range map[string]bool{
		"": true, "bd://1": true, "dvd://": true, "ytdl://abc": true, t.TempDir(): true,
		"/nope/missing.mkv": true, f: false, "https://example.com/a.mp4": false,
	} {
		if got := NeedsMPV(path); got != want {
			t.Errorf("NeedsMPV(%q) = %v", path, got)
		}
	}
}

func TestAudioClipFrame(t *testing.T) {
	video := testVideo(t)
	tools := &Tools{WorkDir: t.TempDir()}
	src := Source{VideoPath: video, AudioPath: video, AudioMap: "0:a:0"}
	ctx := context.Background()

	mp3, err := tools.Audio(ctx, src, 1000, 3500)
	if err != nil {
		t.Fatal(err)
	}
	if len(mp3) < 1000 || !(bytes.HasPrefix(mp3, []byte("ID3")) || mp3[0] == 0xFF) {
		t.Fatalf("not an mp3: %d bytes", len(mp3))
	}

	clip, mimeType, err := tools.Clip(ctx, src, 1000, 7000, 480, 12)
	if err != nil {
		t.Fatal(err)
	}
	if tools.HasWebPAnim() {
		if mimeType != "image/webp" || !bytes.HasPrefix(clip, []byte("RIFF")) || !bytes.Contains(clip[:64], []byte("WEBPVP8X")) {
			t.Fatalf("not an animated webp: %s %q", mimeType, clip[:16])
		}
		if !bytes.Contains(clip, []byte("ANIM")) {
			t.Fatal("webp has no ANIM chunk")
		}
	} else if mimeType != "image/gif" || !bytes.HasPrefix(clip, []byte("GIF8")) {
		t.Fatalf("not a gif: %s", mimeType)
	}
	if len(clip) > 1<<20 {
		t.Fatalf("clip too large: %d bytes", len(clip))
	}
	t.Logf("6 s clip: %d KB %s", len(clip)/1024, mimeType)

	img, err := tools.Frame(ctx, src, 2000)
	if err != nil {
		t.Fatal(err)
	}
	if b := img.Bounds(); b.Dx() != 1280 || b.Dy() != 720 {
		t.Fatalf("frame %v", b)
	}
	if _, err := tools.Audio(ctx, src, 3000, 1000); err == nil {
		t.Fatal("reversed range must fail")
	}
	if _, err := tools.Audio(ctx, Source{VideoPath: filepath.Join(t.TempDir(), "missing.mkv")}, 0, 1000); err == nil {
		t.Fatal("missing file must fail")
	}
}

func TestMPVFallbackForNonFileSources(t *testing.T) {
	video := testVideo(t)
	if _, err := exec.LookPath("mpv"); err != nil {
		t.Skip("mpv not installed")
	}
	tools := &Tools{WorkDir: t.TempDir()}
	// A directory path forces the mpv route (like a disc folder).
	src := Source{VideoPath: t.TempDir(), MPVOpen: &MPVOpen{URL: video, Opts: []string{"--edition=0", "--evil=1"}}}
	mp3, err := tools.Audio(context.Background(), src, 1000, 3000)
	if err != nil {
		t.Fatal(err)
	}
	if len(mp3) < 1000 {
		t.Fatalf("mpv audio too small: %d", len(mp3))
	}
	img, err := tools.Frame(context.Background(), src, 1500)
	if err != nil {
		t.Fatal(err)
	}
	if img.Bounds().Dx() != 1280 {
		t.Fatalf("mpv frame %v", img.Bounds())
	}
	clip, mimeType, err := tools.Clip(context.Background(), src, 1000, 3000, 320, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(clip) < 100 || (mimeType == "image/webp" && !bytes.HasPrefix(clip, []byte("RIFF"))) {
		t.Fatalf("mpv clip %s %d bytes", mimeType, len(clip))
	}
}
