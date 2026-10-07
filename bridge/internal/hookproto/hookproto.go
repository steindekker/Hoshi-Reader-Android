// Package hookproto defines the JSON-lines protocol between vnhook.exe (the
// Windows helper that runs inside the game's Wine prefix and drives
// LunaHook) and the hoshi-bridge daemon.
//
// vnhook writes one Event per line to stdout and reads one Command per line
// from stdin. When stdin reaches EOF, vnhook detaches from the game and
// exits, so the helper never outlives the daemon.
package hookproto

import "fmt"

// Event kinds (Event.Ev).
const (
	EvHello   = "hello"   // vnhook started; Version, Arch, PID (its own Windows pid)
	EvProcess = "process" // a target process changed state; Proc
	EvLog     = "log"     // LunaHook host/hook message; Level, Text
	EvThread  = "thread"  // a text thread was created or destroyed; Op, Thread
	EvText    = "text"    // a text thread produced output; Thread, Text
	EvError   = "error"   // something failed; Text
	EvExit    = "exit"    // vnhook is about to exit; Text is the reason
)

// Process states (Proc.State).
const (
	ProcFound        = "found"        // target process found by name
	ProcInjected     = "injected"     // LunaHook DLL loaded into the target
	ProcConnected    = "connected"    // the hook DLL connected back to the host
	ProcDisconnected = "disconnected" // the hook DLL's pipe closed
	ProcExited       = "exited"       // the target process exited
)

// Log levels (Event.Level), mirroring LunaHook's HOSTINFO.
const (
	LogConsole      = "console"
	LogWarning      = "warning"
	LogEmuWarning   = "emuwarning"
	LogEmuConnected = "emuconnected"
	LogEngine       = "engine" // Text is the detected engine name
)

// Thread ops (Event.Op).
const (
	OpCreate  = "create"
	OpDestroy = "destroy"
)

// Thread identifies one LunaHook text thread (LunaHook's ThreadParam plus
// the hook's code and name).
type Thread struct {
	PID  uint32 `json:"pid"`  // Windows pid of the hooked process
	Addr uint64 `json:"addr"` // hook address
	Ctx  uint64 `json:"ctx"`  // context, usually a return address
	Ctx2 uint64 `json:"ctx2"` // sub-context
	Code string `json:"code"` // LunaHook hook code, e.g. "HS8@0:sgre_steam.exe:render"
	Name string `json:"name"` // hook name, e.g. "render"
}

// Key is a session-unique identifier of the thread.
func (t Thread) Key() string {
	return fmt.Sprintf("%d-%x-%x-%x", t.PID, t.Addr, t.Ctx, t.Ctx2)
}

// Proc describes a target process.
type Proc struct {
	PID   uint32 `json:"pid"`
	Exe   string `json:"exe"`
	State string `json:"state"`
	Bits  int    `json:"bits,omitempty"`
}

// Event is one line written by vnhook.
type Event struct {
	Ev      string  `json:"ev"`
	Version string  `json:"version,omitempty"`
	Arch    string  `json:"arch,omitempty"`
	PID     uint32  `json:"pid,omitempty"`
	Proc    *Proc   `json:"proc,omitempty"`
	Level   string  `json:"level,omitempty"`
	Op      string  `json:"op,omitempty"`
	Thread  *Thread `json:"thread,omitempty"`
	Text    string  `json:"text,omitempty"`
	At      int64   `json:"at,omitempty"` // Unix ms when vnhook saw the event
}

// Command kinds (Command.Cmd).
const (
	CmdInsertHook = "insertHook" // insert a LunaHook hook code (PID 0 = every connected process)
	CmdQuit       = "quit"       // detach and exit
)

// Command is one line read by vnhook.
type Command struct {
	Cmd  string `json:"cmd"`
	PID  uint32 `json:"pid,omitempty"`
	Code string `json:"code,omitempty"`
}
