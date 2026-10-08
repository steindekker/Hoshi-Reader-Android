// Package media cuts sentence audio, animated clips and still frames out of
// the media mpv is playing, with ffmpeg for files and mpv's own encoder for
// sources ffmpeg cannot open (bd://, dvd://, disc folders, ytdl URLs). The
// audio logic follows mpv-anki's helper (mine.py).
package media

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"image"
	_ "image/jpeg" // decode frames
	_ "image/png"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

// Source describes what to cut from.
type Source struct {
	// VideoPath is the media file or a URL ffmpeg can read directly.
	VideoPath string
	// AudioPath is the file holding the selected audio track (an external
	// audio file, or VideoPath).
	AudioPath string
	// AudioMap is the ffmpeg -map of the selected audio track in AudioPath.
	AudioMap string
	// MPVOpen reopens the source in mpv (non-file sources), nil otherwise.
	MPVOpen *MPVOpen
	// AID is mpv's id of the selected audio track ("" = auto).
	AID string
}

// MPVOpen is how mpv opened a non-file source.
type MPVOpen struct {
	URL  string
	Opts []string
}

// Tools runs ffmpeg and mpv.
type Tools struct {
	FFmpeg  string // default "ffmpeg"
	MPV     string // default "mpv"
	WorkDir string // temp files; default os.TempDir()

	once     sync.Once
	webpAnim bool
}

// Clip encoding targets.
const (
	clipQuality    = 55
	clipRetryLimit = 900 << 10 // re-encode smaller above this size
)

var schemeRE = regexp.MustCompile(`^[a-zA-Z][a-zA-Z0-9+.-]*://`)
var mpvOnlyRE = regexp.MustCompile(`(?i)^(bd|bluray|dvd|dvdnav|cdda|ytdl|edl|memory|av|lavf|file)://`)

// NeedsMPV reports whether ffmpeg cannot read path as a plain media file.
func NeedsMPV(path string) bool {
	if path == "" {
		return true
	}
	if mpvOnlyRE.MatchString(path) {
		return true
	}
	if schemeRE.MatchString(path) {
		return false // http(s) etc.: ffmpeg handles direct media URLs
	}
	fi, err := os.Stat(path)
	return err != nil || !fi.Mode().IsRegular()
}

// safeMPVOpt are the reopen options passed through to the encoder mpv.
var safeMPVOpt = regexp.MustCompile(`^--(bluray-device|dvd-device|cdrom-device|edition|audio-files?|audio-file-paths|demuxer-lavf-o|ytdl-format|http-header-fields|user-agent|referrer|cookies-file|cookies)(=.*)?$`)

func (t *Tools) ffmpeg() string {
	if t.FFmpeg == "" {
		return "ffmpeg"
	}
	return t.FFmpeg
}

func (t *Tools) mpv() string {
	if t.MPV == "" {
		return "mpv"
	}
	return t.MPV
}

func (t *Tools) tempDir() (string, error) {
	base := t.WorkDir
	if base == "" {
		base = os.TempDir()
	}
	if err := os.MkdirAll(base, 0o700); err != nil {
		return "", err
	}
	return os.MkdirTemp(base, "media-")
}

// HasWebPAnim reports whether ffmpeg has the libwebp_anim encoder.
func (t *Tools) HasWebPAnim() bool {
	t.once.Do(func() {
		out, err := exec.Command(t.ffmpeg(), "-hide_banner", "-encoders").Output()
		t.webpAnim = err == nil && bytes.Contains(out, []byte("libwebp_anim"))
	})
	return t.webpAnim
}

func run(ctx context.Context, timeout time.Duration, name string, args ...string) error {
	cctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	cmd := exec.CommandContext(cctx, name, args...)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	cmd.Stdout = &stderr
	err := cmd.Run()
	if cctx.Err() == context.DeadlineExceeded {
		return fmt.Errorf("%s timed out after %s", filepath.Base(name), timeout)
	}
	if err != nil {
		lines := strings.Split(strings.TrimSpace(stderr.String()), "\n")
		return fmt.Errorf("%s: %v: %s", filepath.Base(name), err, lines[len(lines)-1])
	}
	return nil
}

func (t *Tools) runFFmpeg(ctx context.Context, isURL bool, args ...string) error {
	timeout := 60 * time.Second
	if isURL {
		timeout = 30 * time.Second
	}
	return run(ctx, timeout, t.ffmpeg(), append([]string{"-hide_banner", "-nostdin", "-v", "error", "-y"}, args...)...)
}

func (t *Tools) runMPV(ctx context.Context, open *MPVOpen, extra ...string) error {
	if open == nil || open.URL == "" {
		return errors.New("no mpv source to reopen")
	}
	args := []string{"--no-config", "--really-quiet", "--no-terminal"}
	for _, o := range open.Opts {
		if safeMPVOpt.MatchString(o) {
			args = append(args, o)
		}
	}
	args = append(args, extra...)
	args = append(args, "--", open.URL)
	return run(ctx, 60*time.Second, t.mpv(), args...)
}

func secs(ms int64) string { return fmt.Sprintf("%.3f", float64(max(ms, 0))/1000) }

func readOutput(path string, minSize int64) ([]byte, error) {
	fi, err := os.Stat(path)
	if err != nil || fi.Size() < minSize {
		return nil, errors.New("encoder produced no output")
	}
	return os.ReadFile(path)
}

// Audio cuts startMs..endMs of the selected audio track as MP3 (libmp3lame,
// 192 kb/s, 48 kHz), like mpv-anki.
func (t *Tools) Audio(ctx context.Context, src Source, startMs, endMs int64) ([]byte, error) {
	if endMs <= startMs {
		return nil, fmt.Errorf("bad audio range %d..%d", startMs, endMs)
	}
	dir, err := t.tempDir()
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(dir)
	out := filepath.Join(dir, "audio.mp3")
	viaMPV := func() error {
		aid := src.AID
		if aid == "" {
			aid = "auto"
		}
		return t.runMPV(ctx, src.MPVOpen,
			"--start="+secs(startMs), "--end="+secs(endMs), "--aid="+aid, "--vid=no", "--sid=no",
			"--o="+out, "--of=mp3", "--oac=libmp3lame", "--oacopts-add=b=192k",
			"--audio-samplerate=48000", "--audio-channels=stereo")
	}
	audioPath := src.AudioPath
	if audioPath == "" {
		audioPath = src.VideoPath
	}
	if src.MPVOpen != nil && NeedsMPV(audioPath) {
		if err := viaMPV(); err != nil {
			return nil, err
		}
		return readOutput(out, 1000)
	}
	amap := src.AudioMap
	if amap == "" {
		amap = "0:a:0"
	}
	err = t.runFFmpeg(ctx, schemeRE.MatchString(audioPath),
		"-vn", "-sn", "-ss", secs(startMs), "-to", secs(endMs), "-i", audioPath,
		"-map", amap, "-map_metadata", "-1",
		"-c:a", "libmp3lame", "-b:a", "192k", "-ar", "48000", out)
	if err != nil && src.MPVOpen != nil { // e.g. a page URL ffmpeg can't open
		if err2 := viaMPV(); err2 != nil {
			return nil, fmt.Errorf("%v; mpv: %v", err, err2)
		}
		err = nil
	}
	if err != nil {
		return nil, err
	}
	return readOutput(out, 1)
}

// scaleFilter squares pixels (anamorphic sources) and fits maxWidth.
func scaleFilter(maxWidth int) string {
	return fmt.Sprintf("scale=iw*sar:ih,setsar=1,scale='min(%d,iw)':-2:flags=lanczos", maxWidth)
}

// mpvFilter adapts an ffmpeg filter chain to mpv's --vf=lavfi=[…]: quotes
// are not available there, so commas inside expressions are escaped.
func mpvFilter(vf string) string {
	return regexp.MustCompile(`'([^']*)'`).ReplaceAllStringFunc(vf, func(q string) string {
		return strings.ReplaceAll(strings.Trim(q, "'"), ",", `\,`)
	})
}

// Clip encodes startMs..endMs as a looping animated WebP without audio
// (GIF when ffmpeg lacks libwebp_anim). It returns the data and its MIME type.
func (t *Tools) Clip(ctx context.Context, src Source, startMs, endMs int64, maxWidth, fps int) ([]byte, string, error) {
	if endMs <= startMs {
		return nil, "", fmt.Errorf("bad clip range %d..%d", startMs, endMs)
	}
	dir, err := t.tempDir()
	if err != nil {
		return nil, "", err
	}
	defer os.RemoveAll(dir)
	webp := t.HasWebPAnim()
	ext, mimeType := "gif", "image/gif"
	if webp {
		ext, mimeType = "webp", "image/webp"
	}
	encode := func(width, quality int) ([]byte, error) {
		out := filepath.Join(dir, fmt.Sprintf("clip-%d.%s", width, ext))
		vf := fmt.Sprintf("fps=%d,%s", fps, scaleFilter(width))
		var codec []string
		if webp {
			codec = []string{"-c:v", "libwebp_anim", "-lossless", "0", "-quality", fmt.Sprint(quality),
				"-compression_level", "5", "-preset", "default", "-loop", "0"}
		} else {
			vf += ",split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4"
			codec = []string{"-loop", "0"}
		}
		if src.MPVOpen != nil && NeedsMPV(src.VideoPath) {
			ovc := []string{"--ovc=gif"}
			if webp {
				ovc = []string{"--ovc=libwebp_anim", fmt.Sprintf("--ovcopts=lossless=0,quality=%d,compression_level=5", quality), "--ofopts=loop=0"}
			}
			args := append([]string{"--start=" + secs(startMs), "--end=" + secs(endMs), "--aid=no", "--sid=no",
				"--vf=lavfi=[" + mpvFilter(vf) + "]", "--o=" + out, "--of=" + ext}, ovc...)
			if err := t.runMPV(ctx, src.MPVOpen, args...); err != nil {
				return nil, err
			}
			return readOutput(out, 1)
		}
		args := []string{"-ss", secs(startMs), "-t", secs(endMs - startMs), "-i", src.VideoPath,
			"-map", "0:v:0", "-an", "-sn", "-dn", "-map_metadata", "-1", "-vf", vf}
		args = append(append(args, codec...), out)
		if err := t.runFFmpeg(ctx, schemeRE.MatchString(src.VideoPath), args...); err != nil {
			return nil, err
		}
		return readOutput(out, 1)
	}
	data, err := encode(maxWidth, clipQuality)
	if err != nil {
		return nil, "", err
	}
	if len(data) > clipRetryLimit {
		if smaller, err := encode(max(maxWidth*3/4, 160), clipQuality-20); err == nil && len(smaller) < len(data) {
			data = smaller
		}
	}
	return data, mimeType, nil
}

// Frame decodes the video frame at atMs.
func (t *Tools) Frame(ctx context.Context, src Source, atMs int64) (image.Image, error) {
	dir, err := t.tempDir()
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(dir)
	out := filepath.Join(dir, "frame.png")
	if src.MPVOpen != nil && NeedsMPV(src.VideoPath) {
		err = t.runMPV(ctx, src.MPVOpen, "--start="+secs(atMs), "--frames=1", "--aid=no", "--sid=no",
			"--ao=null", "--vo=image", "--vo-image-format=png", "--vo-image-outdir="+dir)
		if err != nil {
			return nil, err
		}
		matches, _ := filepath.Glob(filepath.Join(dir, "*.png"))
		if len(matches) == 0 {
			return nil, errors.New("mpv wrote no frame")
		}
		out = matches[0]
	} else {
		err = t.runFFmpeg(ctx, schemeRE.MatchString(src.VideoPath),
			"-ss", secs(atMs), "-i", src.VideoPath, "-map", "0:v:0", "-an", "-sn",
			"-vf", "scale=iw*sar:ih,setsar=1", "-frames:v", "1", "-c:v", "png", "-f", "image2", out)
		if err != nil {
			return nil, err
		}
	}
	return DecodeFile(out)
}

// DecodeFile decodes a PNG or JPEG file.
func DecodeFile(path string) (image.Image, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	img, _, err := image.Decode(f)
	return img, err
}
