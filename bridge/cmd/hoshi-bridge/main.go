// Command hoshi-bridge collects visual-novel sentences from text hookers on
// a Steam Deck and serves them (plus game screenshots) to a phone.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/steindekker/hoshi-reader/bridge/internal/config"
	"github.com/steindekker/hoshi-reader/bridge/internal/hook"
	"github.com/steindekker/hoshi-reader/bridge/internal/hub"
	"github.com/steindekker/hoshi-reader/bridge/internal/media"
	"github.com/steindekker/hoshi-reader/bridge/internal/screenshot"
	"github.com/steindekker/hoshi-reader/bridge/internal/server"
	"github.com/steindekker/hoshi-reader/bridge/internal/session"
	"github.com/steindekker/hoshi-reader/bridge/internal/sources"
	"github.com/steindekker/hoshi-reader/bridge/internal/sources/mpv"
	"github.com/steindekker/hoshi-reader/bridge/internal/status"
	"github.com/steindekker/hoshi-reader/bridge/internal/store"
	"github.com/steindekker/hoshi-reader/bridge/internal/vn"
)

// version is overridden at build time with -ldflags "-X main.version=...".
var version = "0.1.0"

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "hoshi-bridge:", err)
		os.Exit(1)
	}
}

func run() error {
	var (
		configPath  = flag.String("config", config.DefaultPath(), "path to config.json (created with defaults if missing)")
		listen      = flag.String("listen", "", "listen address, e.g. 0.0.0.0:7277 (overrides config)")
		token       = flag.String("token", "", "bearer token required for /api/* (overrides config)")
		dataDir     = flag.String("data-dir", "", "directory for lines.jsonl (overrides config)")
		logLevel    = flag.String("log-level", "", "debug, info, warn or error (overrides config)")
		showVersion = flag.Bool("version", false, "print version and exit")
		shotTest    = flag.String("screenshot-test", "", "capture one screenshot to this .jpg path, report the backend, and exit")
		detectOnly  = flag.Bool("detect", false, "print which screenshot backends are available and exit")
		initOnly    = flag.Bool("init", false, "create the default config if missing, print its path and exit")
		setupOnly   = flag.Bool("setup", false, "download the pinned LunaHook DLLs into the data directory and exit")
		printData   = flag.Bool("print-data-dir", false, "print the data directory from the config and exit")
	)
	flag.Parse()
	if *showVersion {
		fmt.Println("hoshi-bridge", version)
		return nil
	}

	legacy := ""
	if *configPath == config.DefaultPath() {
		legacy = config.LegacyPath()
	}
	cfg, created, err := config.LoadOrMigrate(config.ExpandHome(*configPath), legacy)
	if err != nil {
		return err
	}
	if *initOnly {
		state := "exists"
		if created {
			state = "created"
		}
		fmt.Printf("config %s: %s\n", state, config.ExpandHome(*configPath))
		return nil
	}
	if *printData {
		fmt.Println(config.ExpandHome(cfg.DataDir))
		return nil
	}
	if *setupOnly {
		dir := filepath.Join(config.ExpandHome(cfg.DataDir), "lunahook")
		fmt.Printf("lunahook %s -> %s\n", hook.LunaVersion, dir)
		if err := hook.LunaDist.Ensure(dir); err != nil {
			return err
		}
		fmt.Println("lunahook files ok")
		return nil
	}
	if *listen != "" {
		cfg.Listen = *listen
	}
	if *token != "" {
		cfg.Token = *token
	}
	if *dataDir != "" {
		cfg.DataDir = *dataDir
	}
	if *logLevel != "" {
		cfg.LogLevel = *logLevel
	}
	if err := cfg.Validate(); err != nil {
		return fmt.Errorf("config: %w", err)
	}

	log := newLogger(cfg.LogLevel)
	slog.SetDefault(log)
	if created {
		log.Info("wrote default config", "path", config.ExpandHome(*configPath))
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	var shots *screenshot.Manager
	if cfg.Shot.Enabled || *shotTest != "" || *detectOnly {
		backends, err := screenshot.NewBackends(cfg.Shot.Backends, screenshot.Settings{
			Command:       cfg.Shot.Command,
			GamescopeType: cfg.Shot.GamescopeType,
			Env:           cfg.Shot.Env,
		})
		if err != nil {
			return fmt.Errorf("config: %w", err)
		}
		shots = screenshot.NewManager(backends, time.Duration(cfg.Shot.TimeoutMs)*time.Millisecond, log)
		if *detectOnly {
			return detect(ctx, backends)
		}
		if *shotTest != "" {
			return screenshotTest(ctx, shots, *shotTest)
		}
	}

	h := hub.New()
	reg := session.NewRegistry(h)
	cache := screenshot.NewCache(cfg.Shot.CacheSize)
	var vnCapturer vn.Capturer
	if shots != nil {
		vnCapturer = shots
	}
	vnSession := vn.New(reg, vnCapturer, cache)
	lineIDs := make(chan int64, 16)
	st, err := store.Open(store.Options{
		Path:       filepath.Join(config.ExpandHome(cfg.DataDir), "lines.jsonl"),
		MaxLines:   cfg.MaxLines,
		Dedupe:     cfg.Dedupe,
		Normalizer: mustNormalizer(cfg.Filters),
		OnAdd: func(l store.Line) {
			vnSession.Add(l)
			select {
			case lineIDs <- l.ID:
			default: // on-line screenshot worker busy or off; it only cares about the newest line
			}
		},
		Log: log,
	})
	if err != nil {
		return fmt.Errorf("open line store: %w", err)
	}
	defer st.Close()
	if cfg.VNEnabled() {
		vnSession.Register(st.Last(vn.MaxCues))
	}

	var wg sync.WaitGroup
	tracker := status.NewTracker()
	tracker.SetOnChange(func() { h.Publish(server.StatusChanged{}) })
	tracker.Register("http", "http", "POST /v2/ingest/lines", true)

	dataPath := config.ExpandHome(cfg.DataDir)
	var hookHandle *status.Handle
	if cfg.Hook.Enabled {
		hookHandle = tracker.Register(hook.SourceID, "hook", "LunaHook", false)
	}
	mem, err := hook.OpenMemory(filepath.Join(dataPath, "hook-threads.json"))
	if err != nil {
		log.Warn("thread memory unreadable; starting without remembered choices", "err", err)
	}
	sel := hook.NewSelector(hook.SelectorOptions{
		Memory: mem,
		Log:    log,
		Emit: func(text string) {
			l, res, err := st.Add(text, hook.SourceID)
			switch {
			case err != nil:
				log.Error("store line", "source", hook.SourceID, "err", err)
			case res == store.Added:
				log.Info("line", "id", l.ID, "source", hook.SourceID, "text", l.Text)
			}
		},
	})
	hooks := hook.NewSupervisor(hook.SupervisorOptions{
		Enabled:  cfg.Hook.Enabled,
		DataDir:  dataPath,
		Filter:   hook.Filter{Allow: cfg.Hook.Allow, Deny: cfg.Hook.Deny},
		CodePage: cfg.Hook.CodePage,
		FlushMs:  cfg.Hook.FlushMs,
		Interval: time.Duration(cfg.Hook.ScanIntervalMs) * time.Millisecond,
		Selector: sel,
		OnState: func(connected bool, err error) {
			if hookHandle != nil {
				hookHandle.Set(connected, err)
			}
			h.Publish(hookStateChanged{})
		},
		OnThreads: func() { h.Publish(server.ThreadsChanged{}) },
		Log:       log,
	})
	if cfg.Hook.Enabled {
		wg.Add(1)
		go func() {
			defer wg.Done()
			hooks.Run(ctx)
		}()
		log.Info("automatic hooking enabled")
	}

	for _, sc := range cfg.Sources {
		if !sc.Enabled {
			continue
		}
		src, target := buildSource(sc)
		handle := tracker.Register(sc.ID, sc.Kind, target, false)
		id := sc.ID
		emit := func(text string) {
			l, res, err := st.Add(text, id)
			switch {
			case err != nil:
				log.Error("store line", "source", id, "err", err)
			case res == store.Added:
				log.Debug("line", "id", l.ID, "source", id, "text", l.Text)
			}
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			src.Run(ctx, emit, reportLogger(log, id, target, handle))
		}()
		log.Info("source started", "id", sc.ID, "kind", sc.Kind, "target", target)
	}

	if cfg.MPV.Enabled {
		socket := config.ExpandHome(cfg.MPV.Socket)
		handle := tracker.Register(mpv.SessionID, "mpv", socket, false)
		cacheHome := os.Getenv("XDG_CACHE_HOME")
		if cacheHome == "" {
			home, _ := os.UserHomeDir()
			cacheHome = filepath.Join(home, ".cache")
		}
		src := mpv.New(mpv.Options{
			Socket:   socket,
			Registry: reg,
			Media:    &media.Tools{WorkDir: filepath.Join(os.TempDir(), fmt.Sprintf("hoshi-bridge-%d", os.Getuid()))},
			CacheDir: filepath.Join(cacheHome, "hoshi-bridge", "subs"),
			OCRDirs:  []string{filepath.Join(cacheHome, "mpv-anki", "ocr")},
			Report:   reportLogger(log, mpv.SessionID, socket, handle),
			Log:      log,
		})
		wg.Add(1)
		go func() { defer wg.Done(); src.Run(ctx) }()
		log.Info("mpv source enabled", "socket", socket)
	}

	if shots != nil {
		wg.Add(1)
		go func() { defer wg.Done(); probeLoop(ctx, shots, tracker, log) }()
		if cfg.Shot.OnLine {
			wg.Add(1)
			go func() {
				defer wg.Done()
				screenshot.OnLineWorker(ctx, lineIDs, time.Duration(cfg.Shot.OnLineDelayMs)*time.Millisecond, shots, cache, log)
			}()
		}
	}
	wg.Add(1)
	go func() { defer wg.Done(); followHookTitle(ctx, h, hooks, vnSession) }()

	hostname, _ := os.Hostname()
	srv := server.New(server.Options{
		Version:        version,
		Hostname:       hostname,
		Token:          cfg.Token,
		AllowedOrigins: cfg.AllowedOrigins,
		Registry:       reg,
		Lines:          st,
		Tracker:        tracker,
		Hooks:          hooks,
		Log:            log,
		ReservedIDs:    []string{vn.SessionID, mpv.SessionID},
	})
	ln, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		return err
	}
	log.Info("listening", "addr", ln.Addr().String(), "auth", cfg.Token != "", "version", version)

	errc := make(chan error, 1)
	go func() { errc <- srv.Serve(ln) }()
	select {
	case err := <-errc:
		stop()
		wg.Wait()
		return err
	case <-ctx.Done():
	}
	log.Info("shutting down")
	sctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	err = srv.Shutdown(sctx)
	wg.Wait()
	return err
}

func newLogger(level string) *slog.Logger {
	var l slog.Level
	if err := l.UnmarshalText([]byte(level)); err != nil {
		l = slog.LevelInfo
	}
	return slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: l}))
}

func mustNormalizer(f config.Filters) *store.Normalizer {
	n, err := store.NewNormalizer(store.FilterOptions{
		Drop: f.Drop, Strip: f.Strip, JoinLines: f.JoinLines, MaxLength: f.MaxLineLength,
	})
	if err != nil {
		panic(err) // Validate already compiled every pattern
	}
	return n
}

func buildSource(sc config.Source) (sources.Source, string) {
	switch sc.Kind {
	case config.KindClipboard:
		return &sources.Clipboard{
			Open:     sources.OpenClipboard(sc.Command, sc.Display, sc.Env),
			Interval: time.Duration(sc.IntervalMs) * time.Millisecond,
			Backoff:  sources.Backoff{Min: time.Second, Max: 30 * time.Second},
		}, sources.ClipboardTarget(sc.Command, sc.Display)
	default:
		return &sources.WebSocket{
			URL:     sc.URL,
			Backoff: sources.Backoff{Min: time.Second, Max: 15 * time.Second},
		}, sc.URL
	}
}

// reportLogger logs connection transitions once instead of on every retry.
func reportLogger(log *slog.Logger, id, target string, h *status.Handle) sources.Report {
	var mu sync.Mutex
	connected, lastErr := false, ""
	return func(c bool, err error) {
		h.Set(c, err)
		mu.Lock()
		defer mu.Unlock()
		msg := ""
		if err != nil {
			msg = err.Error()
		}
		switch {
		case c && !connected:
			log.Info("source connected", "id", id, "target", target)
		case !c && connected:
			log.Warn("source disconnected", "id", id, "err", msg)
		case !c && msg != lastErr:
			log.Info("source not reachable (will keep retrying)", "id", id, "err", msg)
		}
		connected, lastErr = c, msg
	}
}

// probeLoop keeps status.screenshot current as the Deck switches between
// Game Mode and Desktop Mode.
func probeLoop(ctx context.Context, m *screenshot.Manager, t *status.Tracker, log *slog.Logger) {
	last := "\x00"
	tick := time.NewTicker(10 * time.Second)
	defer tick.Stop()
	for {
		name := m.Detect(ctx)
		t.SetScreenshot(name != "", name)
		if name != last {
			if name == "" {
				log.Warn("no screenshot backend available")
			} else {
				log.Info("screenshot backend", "backend", name)
			}
			last = name
		}
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}

// hookStateChanged is published when the LunaHook state changes, so the VN
// session title can follow the hooked game.
type hookStateChanged struct{}

func followHookTitle(ctx context.Context, h *hub.Hub, hooks *hook.Supervisor, s *vn.Session) {
	for ctx.Err() == nil {
		sub := h.Subscribe(64)
		s.SetTitle(vn.GameTitle(hooks.View().Game))
		func() {
			defer sub.Close()
			for {
				select {
				case <-ctx.Done():
					return
				case v, ok := <-sub.C:
					if !ok {
						return
					}
					if _, is := v.(hookStateChanged); is {
						s.SetTitle(vn.GameTitle(hooks.View().Game))
					}
				}
			}
		}()
	}
}

func detect(ctx context.Context, backends []screenshot.Backend) error {
	for _, b := range backends {
		cctx, cancel := context.WithTimeout(ctx, 3*time.Second)
		err := b.Available(cctx)
		cancel()
		if err != nil {
			fmt.Printf("%-14s unavailable: %v\n", b.Name(), err)
		} else {
			fmt.Printf("%-14s available\n", b.Name())
		}
	}
	return nil
}

func screenshotTest(ctx context.Context, m *screenshot.Manager, out string) error {
	if !strings.HasSuffix(strings.ToLower(out), ".jpg") && !strings.HasSuffix(strings.ToLower(out), ".jpeg") {
		return errors.New("-screenshot-test path must end in .jpg")
	}
	start := time.Now()
	shot, err := m.Capture(ctx)
	if err != nil {
		return err
	}
	data, err := screenshot.EncodeJPEG(shot.Image, 0, 90)
	if err != nil {
		return err
	}
	if err := os.WriteFile(out, data, 0o644); err != nil {
		return err
	}
	b := shot.Image.Bounds()
	fmt.Printf("captured %dx%d with %q in %s -> %s\n", b.Dx(), b.Dy(), shot.Backend, time.Since(start).Round(time.Millisecond), out)
	return nil
}
