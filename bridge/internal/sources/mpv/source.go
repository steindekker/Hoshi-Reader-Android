// Package mpv follows a running mpv over its JSON IPC socket and exposes it
// as the bridge's "mpv" video session: the full cue list of the selected
// subtitle track, playback state, and control/screenshot/audio/clip commands.
package mpv

import (
	"context"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"log/slog"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/steindekker/hoshi-reader/bridge/internal/media"
	"github.com/steindekker/hoshi-reader/bridge/internal/screenshot"
	"github.com/steindekker/hoshi-reader/bridge/internal/session"
	"github.com/steindekker/hoshi-reader/bridge/internal/subs"
)

// SessionID is the id of the mpv session.
const SessionID = "mpv"

// DefaultSocket is the IPC socket path suggested for mpv.conf
// (input-ipc-server=/tmp/mpvsocket).
const DefaultSocket = "/tmp/mpvsocket"

// Options configures the source.
type Options struct {
	Socket    string
	Registry  *session.Registry
	Media     *media.Tools
	CacheDir  string // extracted subtitle tracks
	OCRDirs   []string
	Report    func(connected bool, err error)
	Log       *slog.Logger
	Poll      time.Duration // socket retry interval, default 2 s
	Heartbeat time.Duration // playback heartbeat, default 2 s
	Debounce  time.Duration // coalesce property bursts, default 150 ms
}

// subTrack is the part of mpv's current-tracks/sub we use.
type subTrack struct {
	ID               int64  `json:"id"`
	Codec            string `json:"codec"`
	Lang             string `json:"lang"`
	External         bool   `json:"external"`
	ExternalFilename string `json:"external-filename"`
	FFIndex          *int   `json:"ff-index"`
}

func (t *subTrack) key() string {
	if t == nil {
		return "none"
	}
	ff := -1
	if t.FFIndex != nil {
		ff = *t.FFIndex
	}
	return fmt.Sprintf("%d|%s|%v|%s|%d", t.ID, t.Codec, t.External, t.ExternalFilename, ff)
}

type state struct {
	path, title, workDir string
	idle                 bool
	sub                  *subTrack
	subDelayMs           int64
	posMs                float64
	posKnown             bool
	paused               bool
	speed                float64
	subText              string
	subStart, subEnd     *float64
}

// loaded is the result of reading a subtitle track.
type loaded struct {
	key  string
	cues []subs.Cue
	err  error // non-nil: stream live sub-text instead
}

// Source is the mpv integration.
type Source struct {
	opts Options

	mu     sync.Mutex
	client *Client
	st     state

	// session-loop owned
	active    bool
	trackKey  string // path|sub key of the loaded/loading track
	raw       *loaded
	live      bool
	liveID    int64
	trackID   string
	pendingPB bool
}

// New creates the source.
func New(opts Options) *Source {
	if opts.Socket == "" {
		opts.Socket = DefaultSocket
	}
	if opts.Log == nil {
		opts.Log = slog.Default()
	}
	if opts.Report == nil {
		opts.Report = func(bool, error) {}
	}
	if opts.Poll <= 0 {
		opts.Poll = 2 * time.Second
	}
	if opts.Heartbeat <= 0 {
		opts.Heartbeat = 2 * time.Second
	}
	if opts.Debounce <= 0 {
		opts.Debounce = 150 * time.Millisecond
	}
	if opts.Media == nil {
		opts.Media = &media.Tools{}
	}
	return &Source{opts: opts}
}

// Capabilities of the mpv session.
var Capabilities = []string{session.CapControl, session.CapScreenshot, session.CapAudio, session.CapClip}

// Run follows mpv until ctx ends, reconnecting whenever mpv restarts.
func (s *Source) Run(ctx context.Context) {
	reported := ""
	for ctx.Err() == nil {
		err := s.connect(ctx)
		if ctx.Err() != nil {
			return
		}
		msg := ""
		if err != nil {
			msg = err.Error()
		}
		if msg != reported {
			s.opts.Report(false, err)
			if err != nil {
				s.opts.Log.Debug("mpv not reachable (will keep retrying)", "socket", s.opts.Socket, "err", err)
			}
			reported = msg
		}
		t := time.NewTimer(s.opts.Poll)
		select {
		case <-ctx.Done():
			t.Stop()
			return
		case <-t.C:
		}
	}
}

var errNoSocket = errors.New("waiting for mpv (no IPC socket)")

func (s *Source) connect(ctx context.Context) error {
	if _, err := os.Stat(s.opts.Socket); err != nil {
		return errNoSocket
	}
	dctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	c, err := Dial(dctx, s.opts.Socket)
	cancel()
	if err != nil {
		return fmt.Errorf("connect %s: %w", s.opts.Socket, err)
	}
	defer c.Close()
	s.mu.Lock()
	s.client = c
	s.st = state{speed: 1}
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		s.client = nil
		s.mu.Unlock()
		s.endSession()
	}()
	var wd string
	_ = c.Get(ctx, "working-directory", &wd)
	s.mu.Lock()
	s.st.workDir = wd
	s.mu.Unlock()
	for i, name := range observed {
		if err := c.Observe(ctx, int64(i+1), name); err != nil {
			return fmt.Errorf("observe %s: %w", name, err)
		}
	}
	s.opts.Report(true, nil)
	s.opts.Log.Info("mpv connected", "socket", s.opts.Socket)
	err = s.loop(ctx, c)
	s.opts.Log.Info("mpv disconnected", "err", err)
	return err
}

var observed = []string{
	"path", "media-title", "current-tracks/sub", "sub-delay", "time-pos", "pause", "speed",
	"idle-active", "sub-text", "sub-start", "sub-end",
}

func (s *Source) loop(ctx context.Context, c *Client) error {
	heartbeat := time.NewTicker(s.opts.Heartbeat)
	defer heartbeat.Stop()
	debounce := time.NewTimer(time.Hour)
	debounce.Stop()
	defer debounce.Stop()
	results := make(chan loaded, 4)
	var loadCancel context.CancelFunc = func() {}
	defer func() { loadCancel() }()

	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case ev, ok := <-c.Events:
			if !ok {
				return ErrClosed
			}
			switch ev.Event {
			case "property-change":
				a := s.apply(ev)
				if a.refresh {
					debounce.Reset(s.opts.Debounce)
				}
				if a.position && s.pendingPB {
					s.pendingPB = false
					a.playback = true
				}
				if a.playback {
					s.publishPlayback(true)
				}
				if a.liveText != "" {
					s.appendLive(a.liveText)
				}
			case "seek", "playback-restart":
				s.pendingPB = true
			}
		case <-debounce.C:
			loadCancel = s.refresh(ctx, results, loadCancel)
		case r := <-results:
			s.mu.Lock()
			current := s.currentKeyLocked()
			s.mu.Unlock()
			if r.key == current && s.trackKey == r.key {
				s.raw = &r
				s.publishTrack()
			}
		case <-heartbeat.C:
			s.publishPlayback(false)
		}
	}
}

// action is what the session loop does after a property change.
type action struct {
	refresh  bool   // session or track may have changed
	playback bool   // publish playback now
	position bool   // time-pos updated
	liveText string // new sub-text line
}

// apply records a property change under the state lock.
func (s *Source) apply(ev Event) (a action) {
	s.mu.Lock()
	defer s.mu.Unlock()
	st := &s.st
	null := len(ev.Data) == 0 || string(ev.Data) == "null"
	switch ev.Name {
	case "path":
		st.path = ""
		_ = json.Unmarshal(ev.Data, &st.path)
		a.refresh = true
	case "media-title":
		st.title = ""
		_ = json.Unmarshal(ev.Data, &st.title)
		a.refresh = true
	case "idle-active":
		_ = json.Unmarshal(ev.Data, &st.idle)
		a.refresh = true
	case "current-tracks/sub":
		st.sub = nil
		if !null {
			var t subTrack
			if json.Unmarshal(ev.Data, &t) == nil {
				st.sub = &t
			}
		}
		a.refresh = true
	case "sub-delay":
		var d float64
		_ = json.Unmarshal(ev.Data, &d)
		st.subDelayMs = int64(math.Round(d * 1000))
		a.refresh = true
	case "time-pos":
		var p float64
		if null || json.Unmarshal(ev.Data, &p) != nil {
			st.posKnown = false
			return a
		}
		expected, known := st.posMs, st.posKnown
		st.posMs, st.posKnown = p*1000, true
		a.position = true
		// A jump we did not see as a seek event, or the first position.
		a.playback = !known || (!st.paused && math.Abs(st.posMs-expected) > 5000)
	case "pause":
		_ = json.Unmarshal(ev.Data, &st.paused)
		a.playback = true
	case "speed":
		_ = json.Unmarshal(ev.Data, &st.speed)
		a.playback = true
	case "sub-start":
		st.subStart = nil
		var v float64
		if !null && json.Unmarshal(ev.Data, &v) == nil {
			st.subStart = &v
		}
	case "sub-end":
		st.subEnd = nil
		var v float64
		if !null && json.Unmarshal(ev.Data, &v) == nil {
			st.subEnd = &v
		}
	case "sub-text":
		var text string
		_ = json.Unmarshal(ev.Data, &text)
		text = strings.TrimSpace(text)
		if text != "" && text != st.subText {
			a.liveText = text
		}
		st.subText = text
	}
	return a
}

func (s *Source) resolve(p string) string {
	if p == "" || filepath.IsAbs(p) || strings.Contains(p, "://") {
		return p
	}
	return filepath.Join(s.st.workDir, p)
}

func (s *Source) currentKeyLocked() string {
	return s.resolve(s.st.path) + "|" + s.st.sub.key()
}

// refresh reconciles the session with the current state: starts, retitles
// or ends it, and (re)loads the subtitle track when it changed.
func (s *Source) refresh(ctx context.Context, results chan<- loaded, cancelPrev context.CancelFunc) context.CancelFunc {
	s.mu.Lock()
	st := s.st
	path := s.resolve(st.path)
	key := s.currentKeyLocked()
	var sub *subTrack
	if st.sub != nil {
		t := *st.sub
		t.ExternalFilename = s.resolve(t.ExternalFilename)
		sub = &t
	}
	s.mu.Unlock()

	if path == "" || st.idle {
		s.endSession()
		return cancelPrev
	}
	title := st.title
	if title == "" {
		title = filepath.Base(path)
	}
	if !s.active {
		s.active = true
		s.opts.Registry.Upsert(session.Info{
			ID: SessionID, Kind: session.KindVideo, Title: title, Source: "mpv",
			Capabilities: Capabilities, TrackID: "",
		}, s, 0)
		s.publishPlayback(true)
	} else {
		s.opts.Registry.Update(SessionID, func(i *session.Info) { i.Title = title })
	}
	if key == s.trackKey {
		if s.raw != nil && !s.live {
			s.publishTrack() // sub-delay may have changed
		}
		return cancelPrev
	}
	cancelPrev()
	s.trackKey, s.raw = key, nil
	lctx, cancel := context.WithCancel(ctx)
	go func() {
		cues, err := s.loadCues(lctx, path, sub)
		if lctx.Err() != nil {
			return
		}
		select {
		case results <- loaded{key: key, cues: cues, err: err}:
		case <-lctx.Done():
		}
	}()
	return cancel
}

func (s *Source) endSession() {
	if !s.active {
		return
	}
	s.active = false
	s.trackKey, s.raw, s.live, s.trackID = "", nil, false, ""
	s.opts.Registry.End(SessionID)
}

func shortHash(parts ...string) string {
	h := sha1.Sum([]byte(strings.Join(parts, "\x00")))
	return hex.EncodeToString(h[:4])
}

// publishTrack sends the loaded track with the current sub-delay applied,
// or switches to live sub-text streaming when it could not be read.
func (s *Source) publishTrack() {
	if !s.active || s.raw == nil {
		return
	}
	s.mu.Lock()
	delay := s.st.subDelayMs
	s.mu.Unlock()
	if s.raw.err != nil {
		trackID := shortHash(s.trackKey, "live")
		if s.live && s.trackID == trackID {
			return
		}
		s.live, s.liveID, s.trackID = true, 0, trackID
		s.opts.Log.Info("mpv subtitles stream live", "reason", s.raw.err)
		s.opts.Registry.ReplaceCues(SessionID, trackID, nil)
		return
	}
	trackID := shortHash(s.trackKey, fmt.Sprint(delay))
	if !s.live && s.trackID == trackID {
		return
	}
	s.live, s.trackID = false, trackID
	cues := make([]session.Cue, len(s.raw.cues))
	for i, c := range s.raw.cues {
		cues[i] = session.Cue{ID: int64(i + 1), Text: c.Text, StartMs: session.Ms(c.StartMs + delay), EndMs: session.Ms(c.EndMs + delay)}
	}
	s.opts.Log.Info("mpv subtitle track", "cues", len(cues), "track", trackID)
	s.opts.Registry.ReplaceCues(SessionID, trackID, cues)
}

// appendLive adds a cue from sub-text while the track could not be read.
func (s *Source) appendLive(text string) {
	if !s.live || !s.active {
		return
	}
	s.mu.Lock()
	st := s.st
	s.mu.Unlock()
	s.liveID++
	c := session.Cue{ID: s.liveID, Text: text, At: time.Now().UnixMilli()}
	if st.subStart != nil && st.subEnd != nil {
		c.StartMs = session.Ms(int64(math.Round(*st.subStart*1000)) + st.subDelayMs)
		c.EndMs = session.Ms(int64(math.Round(*st.subEnd*1000)) + st.subDelayMs)
	} else if st.posKnown {
		c.StartMs = session.Ms(int64(st.posMs))
		c.EndMs = session.Ms(int64(st.posMs) + 3000)
	}
	s.opts.Registry.AppendCues(SessionID, c)
}

// publishPlayback sends the playback state. activity marks a user-visible
// change (pause, seek, speed).
func (s *Source) publishPlayback(activity bool) {
	s.mu.Lock()
	st := s.st
	s.mu.Unlock()
	if !s.active || !st.posKnown {
		return
	}
	s.opts.Registry.SetPlayback(SessionID, session.Playback{
		PositionMs: int64(math.Round(st.posMs)), Paused: st.paused, Speed: st.speed,
	}, activity)
}

// --- subtitle loading -------------------------------------------------------

var errLive = errors.New("track cannot be read up front")

func (s *Source) loadCues(ctx context.Context, path string, t *subTrack) ([]subs.Cue, error) {
	if t == nil {
		return nil, nil // no subtitle track: an empty, timed track
	}
	if t.External && t.ExternalFilename != "" && !strings.Contains(t.ExternalFilename, "://") {
		return readSubFile(t.ExternalFilename, t.Codec)
	}
	if t.External || t.FFIndex == nil || media.NeedsMPV(path) {
		return nil, errLive
	}
	if subs.IsTextCodec(t.Codec) {
		file, err := s.extract(ctx, path, t)
		if err != nil {
			return nil, fmt.Errorf("%w: %v", errLive, err)
		}
		return readSubFile(file, "")
	}
	// Image subtitles (PGS, VobSub): use an OCR'd SRT next to the file or in
	// mpv-anki's cache when one exists.
	if ocr := s.findOCR(path, t); ocr != "" {
		return readSubFile(ocr, "subrip")
	}
	return nil, fmt.Errorf("%w: image subtitles (%s) without an OCR'd .srt", errLive, t.Codec)
}

func readSubFile(path, codec string) ([]subs.Cue, error) {
	format, ok := subs.FormatFor(codec, path)
	if !ok {
		return nil, fmt.Errorf("%w: %s", errLive, filepath.Base(path))
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", errLive, err)
	}
	cues, err := subs.Parse(format, subs.Decode(data))
	if err != nil {
		return nil, fmt.Errorf("%w: %v", errLive, err)
	}
	return cues, nil
}

// extract pulls an embedded text track out with ffmpeg, cached by file
// path, size, mtime and stream.
func (s *Source) extract(ctx context.Context, path string, t *subTrack) (string, error) {
	fi, err := os.Stat(path)
	if err != nil {
		return "", err
	}
	ext, codecArgs := "srt", []string{"-c:s", "srt"}
	if c := strings.ToLower(t.Codec); c == "ass" || c == "ssa" {
		ext, codecArgs = "ass", []string{"-c:s", "copy"}
	}
	dir := s.opts.CacheDir
	if dir == "" {
		dir = filepath.Join(os.TempDir(), "hoshi-bridge-subs")
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	name := shortHash(path, fmt.Sprint(fi.Size()), fmt.Sprint(fi.ModTime().UnixNano()), fmt.Sprint(*t.FFIndex), t.Codec) + "." + ext
	out := filepath.Join(dir, name)
	if fi, err := os.Stat(out); err == nil && fi.Size() > 0 {
		return out, nil
	}
	tmp := out + ".tmp." + ext
	err = runFFmpeg(ctx, s.opts.Media, append([]string{"-i", path, "-map", fmt.Sprintf("0:%d", *t.FFIndex)}, append(codecArgs, tmp)...)...)
	if err != nil {
		os.Remove(tmp)
		return "", err
	}
	return out, os.Rename(tmp, out)
}

// findOCR looks for mpv-anki's OCR output: <stem>.<lang>.ocr<ff-index>.srt
// beside the video or in ~/.cache/mpv-anki/ocr.
func (s *Source) findOCR(path string, t *subTrack) string {
	lang := t.Lang
	if lang == "" {
		lang = "und"
	}
	base := filepath.Base(path)
	stem := strings.TrimSuffix(base, filepath.Ext(base))
	name := fmt.Sprintf("%s.%s.ocr%d.srt", stem, lang, *t.FFIndex)
	dirs := append([]string{filepath.Dir(path)}, s.opts.OCRDirs...)
	for _, d := range dirs {
		p := filepath.Join(d, name)
		if fi, err := os.Stat(p); err == nil && fi.Mode().IsRegular() {
			return p
		}
	}
	return ""
}

// --- commands (session.Handler) ------------------------------------------------

func (s *Source) clientOrErr() (*Client, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.client == nil {
		return nil, errors.New("mpv is not connected")
	}
	return s.client, nil
}

// Control implements session.Handler.
func (s *Source) Control(ctx context.Context, action string, ms *int64) error {
	c, err := s.clientOrErr()
	if err != nil {
		return err
	}
	switch action {
	case "pause":
		return c.Set(ctx, "pause", true)
	case "play":
		return c.Set(ctx, "pause", false)
	case "seek":
		if ms == nil {
			return errors.New("seek needs ms")
		}
		_, err := c.Command(ctx, "seek", float64(*ms)/1000, "absolute")
		return err
	}
	return session.ErrUnsupported
}

// mediaSource asks mpv how to reopen what it is playing (mirrors mpv-anki).
func (s *Source) mediaSource(ctx context.Context) (media.Source, error) {
	c, err := s.clientOrErr()
	if err != nil {
		return media.Source{}, err
	}
	var path, openName string
	if err := c.Get(ctx, "path", &path); err != nil || path == "" {
		return media.Source{}, errors.New("mpv is not playing anything")
	}
	_ = c.Get(ctx, "stream-open-filename", &openName)
	s.mu.Lock()
	path = s.resolve(path)
	openURL := path
	if openName != "" {
		openURL = s.resolve(openName)
	}
	s.mu.Unlock()
	src := media.Source{VideoPath: path, AudioPath: path, AudioMap: "0:a:0"}
	if media.NeedsMPV(path) || openURL != path {
		var opts []string
		for _, p := range []string{"bluray-device", "dvd-device"} {
			var v string
			if c.Get(ctx, p, &v) == nil && v != "" {
				opts = append(opts, "--"+p+"="+expandHome(v))
			}
		}
		var edition, editions float64
		if c.Get(ctx, "current-edition", &edition) == nil && c.Get(ctx, "edition-list/count", &editions) == nil && edition >= 0 && editions > 1 {
			opts = append(opts, fmt.Sprintf("--edition=%d", int(edition)))
		}
		src.MPVOpen = &media.MPVOpen{URL: openURL, Opts: opts}
	}
	var audio struct {
		ID               int64  `json:"id"`
		External         bool   `json:"external"`
		ExternalFilename string `json:"external-filename"`
		FFIndex          *int   `json:"ff-index"`
	}
	if c.Get(ctx, "current-tracks/audio", &audio) == nil {
		src.AID = fmt.Sprint(audio.ID)
		switch {
		case audio.External && audio.ExternalFilename != "":
			s.mu.Lock()
			src.AudioPath = s.resolve(audio.ExternalFilename)
			s.mu.Unlock()
			src.AudioMap = "0:a"
			if src.MPVOpen != nil {
				src.MPVOpen.Opts = append(src.MPVOpen.Opts, "--audio-files="+src.AudioPath)
			}
		case audio.FFIndex != nil:
			src.AudioMap = fmt.Sprintf("0:%d", *audio.FFIndex)
		}
	}
	return src, nil
}

func expandHome(p string) string {
	if strings.HasPrefix(p, "~/") {
		if home, err := os.UserHomeDir(); err == nil {
			return filepath.Join(home, p[2:])
		}
	}
	return p
}

// Screenshot implements session.Handler: the displayed frame when mpv is
// paused inside the cue, otherwise the frame at the cue midpoint.
func (s *Source) Screenshot(ctx context.Context, req session.ScreenshotRequest) (session.Media, error) {
	c, err := s.clientOrErr()
	if err != nil {
		return session.Media{}, err
	}
	s.mu.Lock()
	st := s.st
	s.mu.Unlock()
	pausedInside := false
	if st.paused && st.posKnown {
		pos := int64(st.posMs)
		switch {
		case req.Cue != nil && req.Cue.Timed():
			pausedInside = pos >= *req.Cue.StartMs-200 && pos <= *req.Cue.EndMs+200
		case req.AtMs != nil:
			pausedInside = abs(pos-*req.AtMs) <= 500
		default:
			pausedInside = true
		}
	}
	if req.AtMs == nil && !pausedInside {
		at := int64(st.posMs)
		req.AtMs = &at
	}
	if pausedInside {
		dir := s.opts.Media.WorkDir
		if dir == "" {
			dir = os.TempDir()
		}
		_ = os.MkdirAll(dir, 0o700)
		f, err := os.CreateTemp(dir, "mpv-shot-*.png")
		if err == nil {
			name := f.Name()
			f.Close()
			defer os.Remove(name)
			if _, err := c.Command(ctx, "screenshot-to-file", name, "video"); err == nil {
				if shot, err := media.DecodeFile(name); err == nil {
					return encodeShot(shot, req)
				}
			}
		}
	}
	src, err := s.mediaSource(ctx)
	if err != nil {
		return session.Media{}, err
	}
	frame, err := s.opts.Media.Frame(ctx, src, *req.AtMs)
	if err != nil {
		return session.Media{}, fmt.Errorf("screenshot: %w", err)
	}
	return encodeShot(frame, req)
}

func encodeShot(img image.Image, req session.ScreenshotRequest) (session.Media, error) {
	data, err := screenshot.EncodeJPEG(img, req.MaxWidth, req.Quality)
	if err != nil {
		return session.Media{}, fmt.Errorf("encode screenshot: %w", err)
	}
	return session.Media{Data: data, ContentType: "image/jpeg"}, nil
}

// Audio implements session.Handler: the padded cue range of the selected
// audio track as MP3.
func (s *Source) Audio(ctx context.Context, req session.RangeRequest) (session.Media, error) {
	src, err := s.mediaSource(ctx)
	if err != nil {
		return session.Media{}, err
	}
	data, err := s.opts.Media.Audio(ctx, src, max(req.StartMs-req.PadMs, 0), req.EndMs+req.PadMs)
	if err != nil {
		return session.Media{}, fmt.Errorf("sentence audio: %w", err)
	}
	return session.Media{Data: data, ContentType: "audio/mpeg"}, nil
}

// Clip implements session.Handler: an animated WebP of the cue range.
func (s *Source) Clip(ctx context.Context, req session.RangeRequest) (session.Media, error) {
	src, err := s.mediaSource(ctx)
	if err != nil {
		return session.Media{}, err
	}
	data, mimeType, err := s.opts.Media.Clip(ctx, src, req.StartMs, req.EndMs, req.MaxWidth, req.FPS)
	if err != nil {
		return session.Media{}, fmt.Errorf("clip: %w", err)
	}
	return session.Media{Data: data, ContentType: mimeType}, nil
}

func abs(v int64) int64 {
	if v < 0 {
		return -v
	}
	return v
}

func runFFmpeg(ctx context.Context, t *media.Tools, args ...string) error {
	name := t.FFmpeg
	if name == "" {
		name = "ffmpeg"
	}
	cctx, cancel := context.WithTimeout(ctx, 120*time.Second)
	defer cancel()
	out, err := exec.CommandContext(cctx, name, append([]string{"-hide_banner", "-nostdin", "-v", "error", "-y"}, args...)...).CombinedOutput()
	if err != nil {
		lines := strings.Split(strings.TrimSpace(string(out)), "\n")
		return fmt.Errorf("ffmpeg: %v: %s", err, lines[len(lines)-1])
	}
	return nil
}
