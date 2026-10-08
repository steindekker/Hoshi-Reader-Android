package hook

import (
	"path/filepath"
	"testing"
	"time"

	"github.com/steindekker/hoshi-reader/bridge/internal/hookproto"
)

func TestSelectorAutoManualAndMemory(t *testing.T) {
	now := time.Date(2026, 10, 4, 12, 0, 0, 0, time.UTC)
	mem, err := OpenMemory(filepath.Join(t.TempDir(), "hook-threads.json"))
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	s := NewSelector(SelectorOptions{
		Memory: mem,
		Now:    func() time.Time { return now },
		Emit:   func(text string) { got = append(got, text) },
	})
	dialogue := hookproto.Thread{PID: 4, Addr: 0x1000, Ctx: 0xABC, Ctx2: 1, Code: "HS8@0:game.exe:render", Name: "render"}
	noise := hookproto.Thread{PID: 4, Addr: 0x2000, Ctx: 2, Ctx2: 3, Code: "HQ@0:game.exe", Name: "path"}
	s.AddThread(dialogue, "Game.exe")
	s.AddThread(noise, "Game.exe")
	s.Output(noise, "Game.exe", `C:\game\data.pak`)
	if id, mode := s.Selected(); id != "" || mode != ModeAuto {
		t.Fatalf("noise must not be selected, got %s %s", id, mode)
	}
	s.Output(dialogue, "Game.exe", "「おはよう。今日はいい天気だね。」")
	id, mode := s.Selected()
	if id != dialogue.Key() || mode != ModeAuto {
		t.Fatalf("dialogue not selected: %s %s", id, mode)
	}
	if len(got) != 1 || got[0] != "「おはよう。今日はいい天気だね。」" {
		t.Fatalf("emitted %#v", got)
	}

	noiseID := noise.Key()
	if err := s.Select(&noiseID); err != nil {
		t.Fatal(err)
	}
	if _, mode := s.Selected(); mode != ModeManual {
		t.Fatalf("mode %s", mode)
	}
	choice, ok := mem.Get("game.exe")
	if !ok || !choice.Manual || choice.HookCode != noise.Code {
		t.Fatalf("memory %+v ok=%v", choice, ok)
	}
	// A stronger later line on the other thread must not steal a manual choice.
	before := len(got)
	s.Output(dialogue, "Game.exe", "「これは選ばれないはずだ。」")
	if _, mode := s.Selected(); mode != ModeManual || len(got) != before {
		t.Fatalf("manual choice was overridden, emitted %d", len(got))
	}

	if err := s.Select(nil); err != nil {
		t.Fatal(err)
	}
	if _, ok := mem.Get("game.exe"); ok {
		t.Fatal("returning to auto should forget a manual choice")
	}
	reloaded, err := OpenMemory(mem.path)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := reloaded.Get("game.exe"); ok {
		t.Fatal("forgotten choice was written back")
	}
}
