// Package config loads ~/.config/hoshi-bridge/config.json.
package config

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strings"
)

// Source kinds.
const (
	KindWebSocket = "websocket"
	KindClipboard = "clipboard"
)

// Config is the on-disk configuration. See README.md for every field.
type Config struct {
	Doc            string     `json:"_doc,omitempty"`
	Listen         string     `json:"listen"`
	Token          string     `json:"token"`
	DataDir        string     `json:"dataDir"`
	MaxLines       int        `json:"maxLines"`
	Dedupe         bool       `json:"dedupe"`
	Filters        Filters    `json:"filters"`
	Sources        []Source   `json:"sources"`
	Shot           Screenshot `json:"screenshot"`
	Hook           Hook       `json:"hook"`
	LogLevel       string     `json:"logLevel"`
	AllowedOrigins []string   `json:"allowedOrigins"`
}

// Hook is automatic LunaHook injection into Proton games.
type Hook struct {
	Enabled        bool     `json:"enabled"`
	Allow          []string `json:"allow"` // exe globs; empty = every game that is not denied
	Deny           []string `json:"deny"`  // extra exe globs, added to the built-in deny list
	CodePage       int      `json:"codePage"`
	FlushMs        int      `json:"flushMs"`
	ScanIntervalMs int      `json:"scanIntervalMs"`
}

// Filters clean incoming text.
type Filters struct {
	Drop          []string `json:"drop"`
	Strip         []string `json:"strip"`
	JoinLines     bool     `json:"joinLines"`
	MaxLineLength int      `json:"maxLineLength"`
}

// Source is one text input.
type Source struct {
	ID      string `json:"id"`
	Kind    string `json:"kind"`
	Enabled bool   `json:"enabled"`

	// websocket
	URL string `json:"url,omitempty"`

	// clipboard: Command empty or "x11" reads the X11 clipboard natively;
	// otherwise a shell command such as "wl-paste --no-newline".
	Command    string            `json:"command,omitempty"`
	Display    string            `json:"display,omitempty"`
	IntervalMs int               `json:"intervalMs,omitempty"`
	Env        map[string]string `json:"env,omitempty"`
}

// Screenshot settings.
type Screenshot struct {
	Enabled       bool              `json:"enabled"`
	Backends      []string          `json:"backends"`
	Command       string            `json:"command"`
	TimeoutMs     int               `json:"timeoutMs"`
	GamescopeType uint32            `json:"gamescopeType"`
	OnLine        bool              `json:"onLine"`
	OnLineDelayMs int               `json:"onLineDelayMs"`
	CacheSize     int               `json:"cacheSize"`
	Env           map[string]string `json:"env"`
}

// Default returns the configuration written on first run.
func Default() Config {
	return Config{
		Doc:      "hoshi-bridge config. Field reference: README.md (Configuration). Restart the service after editing.",
		Listen:   "0.0.0.0:7277",
		DataDir:  "~/.local/share/hoshi-bridge",
		MaxLines: 5000,
		Dedupe:   true,
		LogLevel: "info",
		Filters: Filters{
			Drop:          []string{},
			Strip:         []string{},
			JoinLines:     true,
			MaxLineLength: 1000,
		},
		Sources: []Source{
			{ID: "textractor", Kind: KindWebSocket, Enabled: false, URL: "ws://127.0.0.1:6677"},
			{ID: "lunatranslator", Kind: KindWebSocket, Enabled: false, URL: "ws://127.0.0.1:2333/api/ws/text/origin"},
			{ID: "clipboard", Kind: KindClipboard, Enabled: false, Command: "", IntervalMs: 500},
		},
		Hook: Hook{
			Enabled:        true,
			Allow:          []string{},
			Deny:           []string{},
			CodePage:       932,
			FlushMs:        500,
			ScanIntervalMs: 2000,
		},
		AllowedOrigins: []string{},
		Shot: Screenshot{
			Enabled:       true,
			Backends:      []string{},
			Command:       "",
			TimeoutMs:     5000,
			GamescopeType: 1,
			OnLine:        false,
			OnLineDelayMs: 300,
			CacheSize:     20,
			Env:           map[string]string{},
		},
	}
}

// DefaultPath is ~/.config/hoshi-bridge/config.json (honouring
// $XDG_CONFIG_HOME).
func DefaultPath() string {
	return filepath.Join(configBase(), "hoshi-bridge", "config.json")
}

// LegacyPath is where steamdeck-vn-extractor (the bridge's former name) kept
// its config. Load migrates it when the new file does not exist yet.
func LegacyPath() string {
	return filepath.Join(configBase(), "steamdeck-vn-extractor", "config.json")
}

func configBase() string {
	base := os.Getenv("XDG_CONFIG_HOME")
	if base == "" {
		home, _ := os.UserHomeDir()
		base = filepath.Join(home, ".config")
	}
	return base
}

// Load reads path, creating it with Default() if it does not exist.
// created reports whether a default file was written.
func Load(path string) (cfg Config, created bool, err error) {
	return LoadOrMigrate(path, "")
}

// LoadOrMigrate is Load, except that a missing path is first seeded from
// legacy (when non-empty and present). The legacy file keeps its dataDir,
// so an upgraded Steam Deck keeps its line log and LunaHook files.
func LoadOrMigrate(path, legacy string) (cfg Config, created bool, err error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) && legacy != "" && legacy != path {
		if old, lerr := os.ReadFile(legacy); lerr == nil {
			if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
				return cfg, false, err
			}
			if err := os.WriteFile(path, old, 0o600); err != nil {
				return cfg, false, fmt.Errorf("migrate %s: %w", legacy, err)
			}
			data, err = old, nil
		}
	}
	if errors.Is(err, os.ErrNotExist) {
		cfg = Default()
		if err := Write(path, cfg); err != nil {
			return cfg, false, fmt.Errorf("write default config: %w", err)
		}
		return cfg, true, nil
	}
	if err != nil {
		return cfg, false, err
	}
	// Start from defaults so omitted fields keep sensible values.
	cfg = Default()
	var keys map[string]json.RawMessage
	if err := json.Unmarshal(data, &keys); err != nil {
		return cfg, false, fmt.Errorf("parse %s: %w", path, err)
	}
	if _, ok := keys["sources"]; ok {
		// Decoding into the default slice would merge with its elements.
		cfg.Sources = nil
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&cfg); err != nil {
		return cfg, false, fmt.Errorf("parse %s: %w", path, err)
	}
	return cfg, false, nil
}

// Write saves cfg as indented JSON.
func Write(path string, cfg Config) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", "  ")
	if err := enc.Encode(cfg); err != nil {
		return err
	}
	return os.WriteFile(path, buf.Bytes(), 0o600)
}

// ExpandHome replaces a leading "~/" with the home directory.
func ExpandHome(p string) string {
	if p == "~" || strings.HasPrefix(p, "~/") {
		home, err := os.UserHomeDir()
		if err == nil {
			return filepath.Join(home, strings.TrimPrefix(p, "~"))
		}
	}
	return p
}

var idPattern = regexp.MustCompile(`^[A-Za-z0-9._-]+$`)

// Validate checks the configuration for mistakes.
func (c *Config) Validate() error {
	if c.Listen == "" {
		return errors.New("listen must not be empty")
	}
	if c.MaxLines < 1 {
		return errors.New("maxLines must be >= 1")
	}
	for _, p := range append(append([]string{}, c.Filters.Drop...), c.Filters.Strip...) {
		if _, err := regexp.Compile(p); err != nil {
			return fmt.Errorf("filter %q: %w", p, err)
		}
	}
	seen := map[string]bool{"http": true, "lunahook": true}
	for i, s := range c.Sources {
		if !idPattern.MatchString(s.ID) {
			return fmt.Errorf("sources[%d]: id %q must match %s", i, s.ID, idPattern)
		}
		if seen[s.ID] {
			return fmt.Errorf("sources[%d]: duplicate or reserved id %q", i, s.ID)
		}
		seen[s.ID] = true
		switch s.Kind {
		case KindWebSocket:
			if !strings.HasPrefix(s.URL, "ws://") && !strings.HasPrefix(s.URL, "wss://") {
				return fmt.Errorf("sources[%d] (%s): url must start with ws:// or wss://", i, s.ID)
			}
		case KindClipboard:
		default:
			return fmt.Errorf("sources[%d] (%s): unknown kind %q (websocket or clipboard)", i, s.ID, s.Kind)
		}
	}
	if c.Shot.GamescopeType < 1 || c.Shot.GamescopeType > 4 {
		return errors.New("screenshot.gamescopeType must be 1-4")
	}
	if c.Hook.CodePage < 1 || c.Hook.CodePage > 65535 {
		return errors.New("hook.codePage must be 1-65535")
	}
	if c.Hook.FlushMs < 50 || c.Hook.FlushMs > 30000 {
		return errors.New("hook.flushMs must be 50-30000")
	}
	if c.Hook.ScanIntervalMs < 200 || c.Hook.ScanIntervalMs > 60000 {
		return errors.New("hook.scanIntervalMs must be 200-60000")
	}
	for _, g := range append(append([]string{}, c.Hook.Allow...), c.Hook.Deny...) {
		if _, err := path.Match(g, "game.exe"); err != nil {
			return fmt.Errorf("hook glob %q: %w", g, err)
		}
	}
	if c.Shot.CacheSize < 1 {
		c.Shot.CacheSize = 1
	}
	return nil
}
