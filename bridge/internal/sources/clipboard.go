package sources

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"time"

	"github.com/steindekker/hoshi-reader/bridge/internal/desktop"
	"github.com/steindekker/hoshi-reader/bridge/internal/x11"
)

// ClipboardReader returns the current clipboard text.
type ClipboardReader interface {
	Read(ctx context.Context) (string, error)
	Close()
}

// Clipboard polls a ClipboardReader and emits the text whenever it changes.
// The content present at startup is taken as a baseline and not emitted.
type Clipboard struct {
	// Open creates the reader; it is called again after a read error.
	Open     func(ctx context.Context) (ClipboardReader, error)
	Interval time.Duration
	Backoff  Backoff
}

// Run implements Source.
func (c *Clipboard) Run(ctx context.Context, emit Emit, report Report) {
	if c.Interval <= 0 {
		c.Interval = 500 * time.Millisecond
	}
	var (
		last      string
		haveLast  bool
		connected bool
		lastErr   string
	)
	fail := func(err error) {
		if connected || err.Error() != lastErr {
			report(false, err)
		}
		connected, lastErr = false, err.Error()
	}
	for ctx.Err() == nil {
		r, err := c.Open(ctx)
		if err != nil {
			fail(err)
			if !sleep(ctx, c.Backoff.Next()) {
				return
			}
			continue
		}
		for ctx.Err() == nil {
			rctx, cancel := context.WithTimeout(ctx, 3*time.Second)
			text, err := r.Read(rctx)
			cancel()
			if err != nil {
				if ctx.Err() != nil {
					break
				}
				fail(err)
				break
			}
			if !connected {
				connected, lastErr = true, ""
				c.Backoff.Reset()
				report(true, nil)
			}
			if !haveLast {
				last, haveLast = text, true
			} else if text != last {
				last = text
				if strings.TrimSpace(text) != "" {
					emit(text)
				}
			}
			if !sleep(ctx, c.Interval) {
				break
			}
		}
		r.Close()
		if ctx.Err() == nil && !sleep(ctx, c.Backoff.Next()) {
			return
		}
	}
}

// CommandClipboard runs a shell command (e.g. "wl-paste --no-newline" or
// "xclip -o -selection clipboard") and returns its stdout.
type CommandClipboard struct {
	Command string
	Env     []string
}

func (c *CommandClipboard) Read(ctx context.Context) (string, error) {
	cmd := exec.CommandContext(ctx, "/bin/sh", "-c", c.Command)
	cmd.Env = c.Env
	cmd.WaitDelay = time.Second
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		msg := strings.TrimSpace(stderr.String())
		// wl-paste/xclip exit non-zero when the clipboard is simply empty.
		low := strings.ToLower(msg)
		if strings.Contains(low, "nothing is copied") || strings.Contains(low, "no selection") ||
			strings.Contains(low, "target string not available") {
			return "", nil
		}
		if msg != "" {
			return "", fmt.Errorf("%w: %s", err, msg)
		}
		return "", err
	}
	return stdout.String(), nil
}

func (c *CommandClipboard) Close() {}

// OpenClipboard returns an opener for the configured clipboard mode:
// an empty command (or "x11") reads the X11 CLIPBOARD natively, preferring
// gamescope's Xwayland in Game Mode; anything else runs as a shell command.
func OpenClipboard(command, display string, env map[string]string) func(ctx context.Context) (ClipboardReader, error) {
	command = strings.TrimSpace(command)
	if command != "" && command != "x11" {
		return func(ctx context.Context) (ClipboardReader, error) {
			return &CommandClipboard{Command: command, Env: desktop.MergeEnv(desktop.SessionEnv(), env)}, nil
		}
	}
	return func(ctx context.Context) (ClipboardReader, error) {
		d := display
		if d == "" {
			d = pickDisplay(ctx)
		}
		if d == "" {
			return nil, errors.New("no X display found")
		}
		return x11.OpenClipboard(ctx, d)
	}
}

func pickDisplay(ctx context.Context) string {
	cands := desktop.X11Displays()
	if d := os.Getenv("DISPLAY"); d != "" {
		cands = append([]string{d}, cands...)
	}
	if len(cands) == 0 {
		return ""
	}
	fctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if d, err := x11.FindGamescopeDisplay(fctx, cands); err == nil {
		return d
	}
	return cands[0]
}

// Target describes the clipboard source for the status object.
func ClipboardTarget(command, display string) string {
	command = strings.TrimSpace(command)
	if command != "" && command != "x11" {
		return command
	}
	if display != "" {
		return "x11:" + display
	}
	return "x11:auto"
}
