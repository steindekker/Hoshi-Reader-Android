package hook

import (
	"os"
	"testing"
	"time"

	"github.com/steindekker/hoshi-reader/bridge/internal/hookproto"
)

func TestDefaultScannerIsTheCurrentUser(t *testing.T) {
	s := NewSupervisor(SupervisorOptions{Enabled: true})
	if s.opts.Scanner.UID != os.Getuid() {
		t.Fatalf("scanner uid %d, want %d", s.opts.Scanner.UID, os.Getuid())
	}
}

func TestPickGameWaitsForTheNewest(t *testing.T) {
	const hz = 100
	uptime := 100 * time.Second
	old := GameProcess{PID: 1, Exe: "launcher.exe", StartTick: uint64((uptime - 30*time.Second) / time.Second * hz)}
	young := GameProcess{PID: 2, Exe: "game.exe", StartTick: uint64((uptime - time.Second) / time.Second * hz)}
	if g, ok := pickGame([]GameProcess{old, young}, uptime, 3*time.Second); ok {
		t.Fatalf("picked %s while the newest game is only 1s old", g.Exe)
	}
	young.StartTick = uint64((uptime - 5*time.Second) / time.Second * hz)
	g, ok := pickGame([]GameProcess{old, young}, uptime, 3*time.Second)
	if !ok || g.PID != 2 {
		t.Fatalf("got %+v ok=%v", g, ok)
	}
	unknown := GameProcess{PID: 3, Exe: "game.exe"}
	g, ok = pickGame([]GameProcess{unknown}, 0, 3*time.Second)
	if !ok || g.PID != 3 {
		t.Fatalf("unknown age should not block, got %+v ok=%v", g, ok)
	}
}

func TestHandleEventSelectsAndReports(t *testing.T) {
	var lines []string
	var states []bool
	var threadPushes int
	sel := NewSelector(SelectorOptions{Emit: func(text string) { lines = append(lines, text) }})
	s := NewSupervisor(SupervisorOptions{
		Enabled:   true,
		Selector:  sel,
		OnState:   func(connected bool, _ error) { states = append(states, connected) },
		OnThreads: func() { threadPushes++ },
	})
	exe := "sgre_steam.exe"
	s.handleEvent(hookproto.Event{Ev: hookproto.EvProcess, Proc: &hookproto.Proc{PID: 9, Exe: exe, State: hookproto.ProcConnected}}, exe)
	if s.View().State != StateAttached || s.View().Game == nil || *s.View().Game != exe {
		t.Fatalf("status %+v", s.View())
	}
	s.handleEvent(hookproto.Event{Ev: hookproto.EvLog, Level: hookproto.LogEngine, Text: "_5pb_2"}, exe)
	if s.View().Engine == nil || *s.View().Engine != "_5pb_2" {
		t.Fatalf("engine dropped: %+v", s.View())
	}
	th := hookproto.Thread{PID: 9, Addr: 0x10, Ctx: 1, Ctx2: 2, Code: "HVXF@3EF00:sgre_steam.exe", Name: "render"}
	s.handleEvent(hookproto.Event{Ev: hookproto.EvThread, Op: hookproto.OpCreate, Thread: &th}, exe)
	s.handleEvent(hookproto.Event{Ev: hookproto.EvText, Thread: &th, Text: "#FFFFFF;「おはよう。今日はいい天気だね。」"}, exe)
	if s.View().SelectedThreadID == nil || *s.View().SelectedThreadID != th.Key() {
		t.Fatalf("selected %+v", s.View())
	}
	if len(lines) != 1 || lines[0] != "「おはよう。今日はいい天気だね。」" {
		t.Fatalf("lines %#v", lines)
	}
	if len(states) == 0 || !states[0] {
		t.Fatalf("connected not reported: %v", states)
	}
	if threadPushes == 0 {
		t.Fatal("thread list changes were not published")
	}
	snap := s.Threads()
	if len(snap.Threads) != 1 || !snap.Threads[0].Selected || snap.Threads[0].HookCode != th.Code {
		t.Fatalf("snapshot %+v", snap.Threads)
	}
}
