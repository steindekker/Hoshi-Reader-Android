package screenshot

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"image"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/steindekker/hoshi-reader/bridge/internal/desktop"
	"github.com/steindekker/hoshi-reader/bridge/internal/gamescope"
	"github.com/steindekker/hoshi-reader/bridge/internal/x11"
)

// Backend is one way of grabbing the screen.
type Backend interface {
	Name() string
	// Available returns nil when the backend can plausibly capture right
	// now. It must be cheap: it runs on every capture and every probe.
	Available(ctx context.Context) error
	// Capture grabs the screen. out is a scratch .png path the backend may
	// use for its output; it is removed afterwards.
	Capture(ctx context.Context, out string) (image.Image, error)
}

// Settings that backends need.
type Settings struct {
	// Command is a custom shell command; {output} is replaced by the
	// (shell-quoted) path the image must be written to.
	Command string
	// GamescopeType is the gamescope screenshot_type (1 = game only).
	GamescopeType uint32
	// Env is merged into the environment of external commands.
	Env map[string]string
}

// AllNames is the default auto-detection order.
var AllNames = []string{"custom", "gamescope", "gamescopectl", "gamescope-x11", "spectacle", "grim", "import", "ffmpeg"}

// NewBackends builds the backends named in order (AllNames when empty).
func NewBackends(names []string, s Settings) ([]Backend, error) {
	if len(names) == 0 {
		names = AllNames
	}
	if s.GamescopeType == 0 {
		s.GamescopeType = gamescope.TypeBasePlaneOnly
	}
	var out []Backend
	for _, n := range names {
		b, err := newBackend(n, s)
		if err != nil {
			return nil, err
		}
		if b != nil {
			out = append(out, b)
		}
	}
	return out, nil
}

func newBackend(name string, s Settings) (Backend, error) {
	env := func() []string { return desktop.MergeEnv(desktop.SessionEnv(), s.Env) }
	pureX11 := func() error {
		// Under any Wayland compositor (gamescope, KWin Wayland, ...) the
		// Xwayland root window does not contain the composited screen, so
		// X11 grabbers would return black frames.
		if len(desktop.WaylandSockets("wayland-")) > 0 || len(gamescope.SocketPaths()) > 0 ||
			desktop.ProcessRunning("gamescope", "gamescope-wl", "kwin_wayland") {
			return errors.New("only usable in a pure X11 session (a Wayland compositor is running)")
		}
		return nil
	}
	switch name {
	case "custom":
		if strings.TrimSpace(s.Command) == "" {
			return nil, nil // skipped silently when not configured
		}
		return &cmdBackend{name: "custom", shell: s.Command, env: env, async: true}, nil
	case "gamescope":
		return &gamescopeWayland{shotType: s.GamescopeType}, nil
	case "gamescopectl":
		return &cmdBackend{
			name: "gamescopectl",
			argv: []string{"gamescopectl", "screenshot", "{output}"},
			env: func() []string {
				extra := map[string]string{}
				if p := gamescope.SocketPaths(); len(p) > 0 {
					extra["GAMESCOPE_WAYLAND_DISPLAY"] = filepath.Base(p[0])
				}
				return desktop.MergeEnv(desktop.SessionEnv(), extra, s.Env)
			},
			async: true, // gamescope writes the file after gamescopectl returns
			check: func() error {
				if len(gamescope.SocketPaths()) == 0 {
					return errors.New("no gamescope socket in $XDG_RUNTIME_DIR")
				}
				return nil
			},
		}, nil
	case "gamescope-x11":
		return &gamescopeX11{shotType: s.GamescopeType}, nil
	case "spectacle":
		return &cmdBackend{
			name: "spectacle",
			argv: []string{"spectacle", "--background", "--nonotify", "--fullscreen", "--output", "{output}"},
			env:  env,
			check: func() error {
				if !desktop.ProcessRunning("kwin_wayland", "kwin_x11") {
					return errors.New("KWin (Desktop Mode) is not running")
				}
				return nil
			},
		}, nil
	case "grim":
		return &cmdBackend{
			name: "grim",
			argv: []string{"grim", "{output}"},
			env:  env,
			check: func() error {
				if len(desktop.WaylandSockets("wayland-")) == 0 && os.Getenv("WAYLAND_DISPLAY") == "" {
					return errors.New("no Wayland compositor socket")
				}
				if desktop.ProcessRunning("kwin_wayland", "gamescope", "gamescope-wl") {
					return errors.New("compositor has no wlr-screencopy support")
				}
				return nil
			},
		}, nil
	case "import":
		return &cmdBackend{
			name:  "import",
			argv:  []string{"import", "-window", "root", "{output}"},
			env:   env,
			check: func() error { return firstErr(needX11(), pureX11()) },
		}, nil
	case "ffmpeg":
		return &cmdBackend{
			name:  "ffmpeg",
			argv:  []string{"ffmpeg", "-loglevel", "error", "-y", "-f", "x11grab", "-i", "{display}", "-frames:v", "1", "{output}"},
			env:   env,
			check: func() error { return firstErr(needX11(), pureX11()) },
		}, nil
	default:
		return nil, fmt.Errorf("unknown screenshot backend %q (known: %s)", name, strings.Join(AllNames, ", "))
	}
}

func firstErr(errs ...error) error {
	for _, e := range errs {
		if e != nil {
			return e
		}
	}
	return nil
}

func needX11() error {
	if os.Getenv("DISPLAY") == "" && len(desktop.X11Displays()) == 0 {
		return errors.New("no X display")
	}
	return nil
}

// gamescopeWayland talks gamescope_control directly (no external tools).
type gamescopeWayland struct{ shotType uint32 }

func (g *gamescopeWayland) Name() string { return "gamescope" }

func (g *gamescopeWayland) connect(ctx context.Context) (*gamescope.Client, error) {
	paths := gamescope.SocketPaths()
	if len(paths) == 0 {
		return nil, errors.New("no gamescope socket in $XDG_RUNTIME_DIR")
	}
	var lastErr error
	for _, p := range paths {
		c, err := gamescope.Connect(ctx, p)
		if err == nil {
			return c, nil
		}
		lastErr = fmt.Errorf("%s: %w", filepath.Base(p), err)
	}
	return nil, lastErr
}

func (g *gamescopeWayland) Available(ctx context.Context) error {
	c, err := g.connect(ctx)
	if err != nil {
		return err
	}
	return c.Close()
}

func (g *gamescopeWayland) Capture(ctx context.Context, out string) (image.Image, error) {
	c, err := g.connect(ctx)
	if err != nil {
		return nil, err
	}
	defer c.Close()
	start := time.Now()
	if err := c.TakeScreenshot(ctx, out, g.shotType); err != nil {
		return nil, err
	}
	return waitForImage(ctx, out, start.Add(-time.Second))
}

// gamescopeX11 uses the root-window property request on gamescope's Xwayland.
type gamescopeX11 struct{ shotType uint32 }

// GamescopeTmpDir is where gamescope writes property-requested screenshots.
var GamescopeTmpDir = "/tmp"

func (g *gamescopeX11) Name() string { return "gamescope-x11" }

func (g *gamescopeX11) display(ctx context.Context) (string, error) {
	cands := desktop.X11Displays()
	if d := os.Getenv("DISPLAY"); d != "" {
		cands = append([]string{d}, cands...)
	}
	if len(cands) == 0 {
		return "", errors.New("no X display")
	}
	return x11.FindGamescopeDisplay(ctx, cands)
}

func (g *gamescopeX11) Available(ctx context.Context) error {
	_, err := g.display(ctx)
	return err
}

func (g *gamescopeX11) Capture(ctx context.Context, _ string) (image.Image, error) {
	d, err := g.display(ctx)
	if err != nil {
		return nil, err
	}
	start := time.Now().Add(-200 * time.Millisecond) // mtime granularity slack
	if err := x11.RequestGamescopeScreenshot(ctx, d, g.shotType); err != nil {
		return nil, err
	}
	path := newestGamescopePNG(start)
	if path == "" {
		return nil, errors.New("gamescope acknowledged the request but wrote no /tmp/gamescope*.png")
	}
	return waitForImage(ctx, path, start)
}

func newestGamescopePNG(notBefore time.Time) string {
	matches, _ := filepath.Glob(filepath.Join(GamescopeTmpDir, "gamescope*.png"))
	type fm struct {
		p string
		t time.Time
	}
	var fs []fm
	for _, m := range matches {
		if st, err := os.Stat(m); err == nil && !st.ModTime().Before(notBefore) {
			fs = append(fs, fm{m, st.ModTime()})
		}
	}
	if len(fs) == 0 {
		return ""
	}
	sort.Slice(fs, func(i, j int) bool { return fs[i].t.After(fs[j].t) })
	return fs[0].p
}

// cmdBackend runs an external tool that writes an image file.
type cmdBackend struct {
	name  string
	argv  []string // {output} / {display} placeholders
	shell string   // alternative to argv: run via /bin/sh -c
	env   func() []string
	check func() error
	// async: the file may appear after the command exits.
	async bool
}

func (c *cmdBackend) Name() string { return c.name }

func (c *cmdBackend) Available(ctx context.Context) error {
	if c.shell == "" {
		if _, ok := desktop.LookPath(c.argv[0]); !ok {
			return fmt.Errorf("%s not installed", c.argv[0])
		}
	}
	if c.check != nil {
		return c.check()
	}
	return nil
}

func (c *cmdBackend) Capture(ctx context.Context, out string) (image.Image, error) {
	env := c.env()
	display := lookupEnv(env, "DISPLAY")
	var cmd *exec.Cmd
	if c.shell != "" {
		line := strings.ReplaceAll(c.shell, "{output}", shellQuote(out))
		line = strings.ReplaceAll(line, "{display}", shellQuote(display))
		cmd = exec.CommandContext(ctx, "/bin/sh", "-c", line)
	} else {
		bin, _ := desktop.LookPath(c.argv[0])
		args := make([]string, 0, len(c.argv)-1)
		for _, a := range c.argv[1:] {
			a = strings.ReplaceAll(a, "{output}", out)
			a = strings.ReplaceAll(a, "{display}", display)
			args = append(args, a)
		}
		cmd = exec.CommandContext(ctx, bin, args...)
	}
	cmd.Env = env
	cmd.WaitDelay = time.Second
	var outBuf bytes.Buffer
	cmd.Stdout = &outBuf
	cmd.Stderr = &outBuf
	start := time.Now().Add(-200 * time.Millisecond)
	if err := cmd.Run(); err != nil {
		msg := strings.TrimSpace(outBuf.String())
		if len(msg) > 300 {
			msg = msg[:300] + "…"
		}
		if msg != "" {
			return nil, fmt.Errorf("%s: %w: %s", c.name, err, msg)
		}
		return nil, fmt.Errorf("%s: %w", c.name, err)
	}
	if !c.async {
		if img, err := DecodeFile(out); err == nil {
			return img, nil
		}
	}
	return waitForImage(ctx, out, start)
}

func lookupEnv(env []string, key string) string {
	for i := len(env) - 1; i >= 0; i-- {
		if k, v, ok := strings.Cut(env[i], "="); ok && k == key {
			return v
		}
	}
	return ""
}

func shellQuote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}
