package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadCreatesDefault(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sub", "config.json")
	cfg, created, err := Load(path)
	if err != nil || !created {
		t.Fatalf("Load: created=%v err=%v", created, err)
	}
	if cfg.Listen != "0.0.0.0:7277" || len(cfg.Sources) != 3 || cfg.Sources[0].URL != "ws://127.0.0.1:6677" || cfg.Sources[0].Enabled {
		t.Fatalf("unexpected default %+v", cfg)
	}
	if !cfg.Hook.Enabled || cfg.Hook.CodePage != 932 || cfg.Hook.FlushMs != 500 {
		t.Fatalf("hook defaults %+v", cfg.Hook)
	}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("default config invalid: %v", err)
	}
	again, created, err := Load(path)
	if err != nil || created {
		t.Fatalf("second Load: created=%v err=%v", created, err)
	}
	if again.Listen != cfg.Listen || len(again.Sources) != 3 || again.Shot.TimeoutMs != 5000 {
		t.Fatalf("roundtrip mismatch %+v", again)
	}
}

func TestLoadPartialKeepsDefaults(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	os.WriteFile(path, []byte(`{"listen":"127.0.0.1:9000","screenshot":{"onLine":true}}`), 0o644)
	cfg, _, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Listen != "127.0.0.1:9000" || !cfg.Shot.OnLine || cfg.Shot.OnLineDelayMs != 300 || cfg.Shot.GamescopeType != 1 || cfg.MaxLines != 5000 {
		t.Fatalf("defaults lost: %+v", cfg)
	}
	if len(cfg.Sources) != 3 {
		t.Fatalf("omitted sources should keep defaults, got %d", len(cfg.Sources))
	}

	os.WriteFile(path, []byte(`{"sources":[{"id":"luna","kind":"websocket","enabled":true,"url":"ws://127.0.0.1:2333/api/ws/text/origin"}]}`), 0o644)
	cfg, _, err = Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Sources) != 1 || cfg.Sources[0].ID != "luna" || cfg.Sources[0].Command != "" {
		t.Fatalf("explicit sources must replace defaults: %+v", cfg.Sources)
	}
}

func TestLoadRejectsUnknownFields(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	os.WriteFile(path, []byte(`{"lisen":"x"}`), 0o644)
	if _, _, err := Load(path); err == nil || !strings.Contains(err.Error(), "lisen") {
		t.Fatalf("typo should be reported, got %v", err)
	}
}

func TestValidate(t *testing.T) {
	bad := map[string]func(*Config){
		"empty listen":   func(c *Config) { c.Listen = "" },
		"bad regex":      func(c *Config) { c.Filters.Drop = []string{"("} },
		"dup id":         func(c *Config) { c.Sources[1].ID = c.Sources[0].ID },
		"reserved id":    func(c *Config) { c.Sources[0].ID = "http" },
		"bad id":         func(c *Config) { c.Sources[0].ID = "a b" },
		"bad kind":       func(c *Config) { c.Sources[0].Kind = "tcp" },
		"bad url":        func(c *Config) { c.Sources[0].URL = "http://x" },
		"bad shot type":  func(c *Config) { c.Shot.GamescopeType = 9 },
		"zero max lines": func(c *Config) { c.MaxLines = 0 },
	}
	for name, mut := range bad {
		c := Default()
		mut(&c)
		if err := c.Validate(); err == nil {
			t.Errorf("%s: expected error", name)
		}
	}
}

func TestExpandHome(t *testing.T) {
	t.Setenv("HOME", "/home/deck")
	if got := ExpandHome("~/.local/share/x"); got != "/home/deck/.local/share/x" {
		t.Fatal(got)
	}
	if got := ExpandHome("/abs"); got != "/abs" {
		t.Fatal(got)
	}
}

func TestLoadOrMigrateCopiesLegacyConfig(t *testing.T) {
	dir := t.TempDir()
	legacy := filepath.Join(dir, "old", "config.json")
	path := filepath.Join(dir, "new", "config.json")
	old := Default()
	old.DataDir = "~/.local/share/steamdeck-vn-extractor"
	old.Token = "s3cret"
	if err := Write(legacy, old); err != nil {
		t.Fatal(err)
	}
	cfg, created, err := LoadOrMigrate(path, legacy)
	if err != nil || created {
		t.Fatalf("created=%v err=%v", created, err)
	}
	if cfg.Token != "s3cret" || cfg.DataDir != old.DataDir {
		t.Fatalf("legacy values not kept: %+v", cfg)
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("migrated config not written: %v", err)
	}
}
