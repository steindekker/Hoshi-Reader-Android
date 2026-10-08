//go:build windows

// Command vnhook is a headless LunaHook host. It runs inside a game's Wine
// prefix (started by hoshi-bridge), injects LunaHook into the game process,
// and streams every text thread's output to stdout as JSON lines (see
// internal/hookproto). It has no window and exits when the game exits, when
// stdin closes, or when Wine shuts the prefix down.
//
//	vnhook.exe -exe game.exe [-exe other.exe] [-codepage 932] [-flush 500]
//	vnhook.exe -pid 1234
//	vnhook.exe -inject 1234 C:\path\LunaHook32.dll   (injector helper mode)
package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"github.com/steindekker/hoshi-reader/bridge/internal/hookproto"
)

var version = "dev"

type multiFlag []string

func (m *multiFlag) String() string     { return strings.Join(*m, ",") }
func (m *multiFlag) Set(v string) error { *m = append(*m, v); return nil }

var (
	kernel32               = syscall.NewLazyDLL("kernel32.dll")
	ntdll                  = syscall.NewLazyDLL("ntdll.dll")
	procVirtualAllocEx     = kernel32.NewProc("VirtualAllocEx")
	procVirtualFreeEx      = kernel32.NewProc("VirtualFreeEx")
	procWriteProcessMemory = kernel32.NewProc("WriteProcessMemory")
	procCreateRemoteThread = kernel32.NewProc("CreateRemoteThread")
	procGetExitCodeThread  = kernel32.NewProc("GetExitCodeThread")
	procIsWow64Process     = kernel32.NewProc("IsWow64Process")
	procLoadLibraryW       = kernel32.NewProc("LoadLibraryW")
	procNtSetInfoProcess   = ntdll.NewProc("NtSetInformationProcess")
)

const (
	processInjectAccess = 0x0002 | 0x0400 | 0x0008 | 0x0020 | 0x0010 | syscall.SYNCHRONIZE // CREATE_THREAD|QUERY_INFORMATION|VM_OPERATION|VM_WRITE|VM_READ
	processQueryLimited = 0x1000
	memCommitReserve    = 0x1000 | 0x2000
	memRelease          = 0x8000
	pageReadWrite       = 0x04
	th32csSnapProcess   = 0x2
	// Wine's private NtSetInformationProcess class that turns the caller
	// into a "system" process: it no longer keeps the wineserver alive and
	// receives an event when the last user process (the game) exits.
	processWineMakeProcessSystem = 1000
)

// out serializes events to stdout.
type out struct {
	mu  sync.Mutex
	enc *json.Encoder
}

func (o *out) emit(e hookproto.Event) {
	if e.At == 0 {
		e.At = time.Now().UnixMilli()
	}
	o.mu.Lock()
	defer o.mu.Unlock()
	_ = o.enc.Encode(e)
}

func (o *out) errorf(format string, a ...any) {
	o.emit(hookproto.Event{Ev: hookproto.EvError, Text: fmt.Sprintf(format, a...)})
}

var stdout = func() *out {
	enc := json.NewEncoder(os.Stdout)
	enc.SetEscapeHTML(false)
	return &out{enc: enc}
}()

func main() {
	var (
		exes      multiFlag
		pids      multiFlag
		hooks     multiFlag
		codepage  = flag.Int("codepage", 932, "default code page for non-Unicode hooks (932 = Shift-JIS)")
		flush     = flag.Int("flush", 500, "milliseconds of silence after which a text thread's buffer becomes a sentence")
		pcHooks   = flag.Bool("pchooks", false, "also insert generic GDI/D3DX text hooks")
		waitFor   = flag.Duration("wait", 2*time.Minute, "how long to wait for the target process to appear")
		dllDir    = flag.String("dll-dir", "", "directory with LunaHost64.dll / LunaHook64.dll / LunaHook32.dll (default: next to vnhook.exe)")
		injectPID = flag.Int("inject", 0, "helper mode: load the DLL given as the only argument into this pid and exit")
		showVer   = flag.Bool("version", false, "print version and exit")
	)
	flag.Var(&exes, "exe", "target executable name, e.g. game.exe (repeatable)")
	flag.Var(&pids, "pid", "target Windows process id (repeatable)")
	flag.Var(&hooks, "hook", "extra LunaHook hook code to insert after attaching (repeatable)")
	flag.Parse()

	if *showVer {
		fmt.Println("vnhook", version, runtime.GOARCH)
		return
	}
	if *injectPID != 0 {
		if flag.NArg() != 1 {
			fmt.Fprintln(os.Stderr, "usage: vnhook -inject <pid> <dll>")
			os.Exit(2)
		}
		if err := injectLocal(uint32(*injectPID), flag.Arg(0)); err != nil {
			fmt.Fprintln(os.Stderr, "inject:", err)
			os.Exit(1)
		}
		return
	}
	if len(exes) == 0 && len(pids) == 0 {
		fmt.Fprintln(os.Stderr, "vnhook: give -exe <name> or -pid <pid>")
		os.Exit(2)
	}
	dir := *dllDir
	if dir == "" {
		self, err := os.Executable()
		if err != nil {
			fmt.Fprintln(os.Stderr, "vnhook:", err)
			os.Exit(1)
		}
		dir = filepath.Dir(self)
	}
	h := &host{
		dir:      dir,
		exes:     exes,
		hooks:    hooks,
		targets:  map[uint32]*target{},
		connects: make(chan uint32, 16),
	}
	for _, p := range pids {
		n, err := strconv.ParseUint(p, 10, 32)
		if err != nil {
			fmt.Fprintln(os.Stderr, "vnhook: bad -pid", p)
			os.Exit(2)
		}
		h.pids = append(h.pids, uint32(n))
	}
	os.Exit(h.run(*codepage, *flush, *pcHooks, *waitFor))
}

type target struct {
	pid       uint32
	exe       string
	handle    syscall.Handle
	connected bool
	since     time.Time
	warned    bool
}

type host struct {
	dir      string
	exes     []string
	pids     []uint32
	hooks    []string
	mu       sync.Mutex
	targets  map[uint32]*target
	seen     bool // at least one target was found
	connects chan uint32

	lunaConnect, lunaCheckInject, lunaDetach, lunaInsertHook *syscall.LazyProc
}

func (h *host) run(codepage, flush int, pcHooks bool, waitFor time.Duration) int {
	stdout.emit(hookproto.Event{Ev: hookproto.EvHello, Version: version, Arch: runtime.GOARCH, PID: uint32(os.Getpid())})
	shutdown := makeSystemProcess()

	dllName := "LunaHost64.dll"
	if runtime.GOARCH == "386" {
		dllName = "LunaHost32.dll"
	}
	dll := syscall.NewLazyDLL(filepath.Join(h.dir, dllName))
	if err := dll.Load(); err != nil {
		stdout.errorf("load %s: %v", dllName, err)
		stdout.emit(hookproto.Event{Ev: hookproto.EvExit, Text: "LunaHost not loadable"})
		return 1
	}
	start := dll.NewProc("Luna_Start")
	settings := dll.NewProc("Luna_Settings")
	h.lunaConnect = dll.NewProc("Luna_ConnectProcess")
	h.lunaCheckInject = dll.NewProc("Luna_CheckIfNeedInject")
	h.lunaDetach = dll.NewProc("Luna_DetachProcess")
	h.lunaInsertHook = dll.NewProc("Luna_InsertHookCode")
	for _, p := range []*syscall.LazyProc{start, settings, h.lunaConnect, h.lunaCheckInject, h.lunaDetach, h.lunaInsertHook} {
		if err := p.Find(); err != nil {
			stdout.errorf("%s: %v", dllName, err)
			stdout.emit(hookproto.Event{Ev: hookproto.EvExit, Text: "LunaHost API mismatch"})
			return 1
		}
	}

	cbs := h.callbacks()
	start.Call(cbs[:]...)
	settings.Call(uintptr(flush), uintptr(codepage), 3000, 20000, boolArg(pcHooks))

	cmds := make(chan hookproto.Command, 16)
	go readCommands(cmds)

	deadline := time.Now().Add(waitFor)
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	h.scan()
	for {
		select {
		case c, ok := <-cmds:
			if !ok || c.Cmd == hookproto.CmdQuit {
				return h.quit("stdin closed")
			}
			if c.Cmd == hookproto.CmdInsertHook && c.Code != "" {
				h.insertHook(c.PID, c.Code)
			}
		case pid := <-h.connects:
			for _, code := range h.hooks {
				h.insertHook(pid, code)
			}
		case <-tick.C:
			if shutdown != 0 {
				if ev, _ := syscall.WaitForSingleObject(shutdown, 0); ev == syscall.WAIT_OBJECT_0 {
					return h.quit("wine prefix shutting down")
				}
			}
			h.scan()
			h.mu.Lock()
			alive := 0
			for pid, t := range h.targets {
				if ev, _ := syscall.WaitForSingleObject(t.handle, 0); ev == syscall.WAIT_OBJECT_0 {
					stdout.emit(hookproto.Event{Ev: hookproto.EvProcess, Proc: &hookproto.Proc{PID: pid, Exe: t.exe, State: hookproto.ProcExited}})
					syscall.CloseHandle(t.handle)
					delete(h.targets, pid)
					continue
				}
				alive++
				if !t.connected && !t.warned && time.Since(t.since) > 20*time.Second {
					t.warned = true
					stdout.errorf("hook DLL in %s (pid %d) did not connect within 20 s", t.exe, pid)
				}
			}
			everSeen := h.seen
			h.mu.Unlock()
			if alive == 0 {
				if everSeen {
					return h.quit("game exited")
				}
				if time.Now().After(deadline) {
					return h.quit("target process not found")
				}
			}
		}
	}
}

func boolArg(b bool) uintptr {
	if b {
		return 1
	}
	return 0
}

// quit detaches from every connected process so the hook DLL unloads
// cleanly (and can be injected again later), then reports the exit.
func (h *host) quit(reason string) int {
	h.mu.Lock()
	n := 0
	for pid, t := range h.targets {
		if t.connected {
			h.lunaDetach.Call(uintptr(pid))
			n++
		}
	}
	h.mu.Unlock()
	if n > 0 {
		time.Sleep(500 * time.Millisecond) // Luna sends the detach request asynchronously
	}
	stdout.emit(hookproto.Event{Ev: hookproto.EvExit, Text: reason})
	return 0
}

func (h *host) insertHook(pid uint32, code string) {
	p, err := syscall.UTF16PtrFromString(code)
	if err != nil {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	for tp, t := range h.targets {
		if t.connected && (pid == 0 || pid == tp) {
			h.lunaInsertHook.Call(uintptr(tp), uintptr(unsafe.Pointer(p)))
		}
	}
	runtime.KeepAlive(p)
}

// scan finds new target processes and attaches to them.
func (h *host) scan() {
	var found []hookproto.Proc
	if len(h.pids) > 0 {
		for _, pid := range h.pids {
			found = append(found, hookproto.Proc{PID: pid, Exe: processName(pid)})
		}
	} else {
		procs, err := listProcesses()
		if err != nil {
			stdout.errorf("list processes: %v", err)
			return
		}
		self := uint32(os.Getpid())
		for _, p := range procs {
			if p.PID == self {
				continue
			}
			for _, want := range h.exes {
				if strings.EqualFold(p.Exe, want) {
					found = append(found, p)
					break
				}
			}
		}
	}
	for _, p := range found {
		h.mu.Lock()
		_, known := h.targets[p.PID]
		h.mu.Unlock()
		if !known {
			h.attach(p)
		}
	}
}

func (h *host) attach(p hookproto.Proc) {
	handle, err := syscall.OpenProcess(syscall.SYNCHRONIZE|processQueryLimited, false, p.PID)
	if err != nil {
		return // already gone or not ours
	}
	bits := processBits(p.PID)
	p.Bits = bits
	p.State = hookproto.ProcFound
	stdout.emit(hookproto.Event{Ev: hookproto.EvProcess, Proc: &p})
	h.mu.Lock()
	h.targets[p.PID] = &target{pid: p.PID, exe: p.Exe, handle: handle, since: time.Now()}
	h.seen = true
	h.mu.Unlock()

	h.lunaConnect.Call(uintptr(p.PID))
	need, _, _ := h.lunaCheckInject.Call(uintptr(p.PID))
	if need&0xff == 0 {
		stdout.emit(hookproto.Event{Ev: hookproto.EvLog, Level: hookproto.LogConsole,
			Text: fmt.Sprintf("LunaHook is already loaded in pid %d; waiting for it to reconnect", p.PID)})
		return
	}
	go func() {
		var err error
		if bits == 32 && runtime.GOARCH == "amd64" {
			err = injectVia32BitHelper(h.dir, p.PID, filepath.Join(h.dir, "LunaHook32.dll"))
		} else {
			name := "LunaHook64.dll"
			if bits == 32 {
				name = "LunaHook32.dll"
			}
			err = injectLocal(p.PID, filepath.Join(h.dir, name))
		}
		if err != nil {
			stdout.errorf("inject into %s (pid %d): %v", p.Exe, p.PID, err)
			return
		}
		q := p
		q.State = hookproto.ProcInjected
		stdout.emit(hookproto.Event{Ev: hookproto.EvProcess, Proc: &q})
	}()
}

// ---- LunaHost callbacks ----

// threadParam mirrors LunaHook's ThreadParam. On x64 it is passed to
// callbacks by reference (structs larger than 8 bytes).
type threadParam struct {
	ProcessID uint32
	Addr      uint64
	Ctx       uint64
	Ctx2      uint64
}

func toThread(code *uint16, name *byte, tp *threadParam) *hookproto.Thread {
	t := &hookproto.Thread{Code: utf16PtrToString(code), Name: bytePtrToString(name)}
	if tp != nil {
		t.PID, t.Addr, t.Ctx, t.Ctx2 = tp.ProcessID, tp.Addr, tp.Ctx, tp.Ctx2
	}
	return t
}

func (h *host) callbacks() [10]uintptr {
	onConnect := func(pid uintptr) uintptr {
		id := uint32(pid)
		h.mu.Lock()
		exe := ""
		if t, ok := h.targets[id]; ok {
			t.connected = true
			exe = t.exe
		}
		h.mu.Unlock()
		stdout.emit(hookproto.Event{Ev: hookproto.EvProcess, Proc: &hookproto.Proc{PID: id, Exe: exe, State: hookproto.ProcConnected}})
		select {
		case h.connects <- id:
		default:
		}
		return 0
	}
	onDisconnect := func(pid uintptr) uintptr {
		id := uint32(pid)
		h.mu.Lock()
		exe := ""
		if t, ok := h.targets[id]; ok {
			t.connected = false
			exe = t.exe
		}
		h.mu.Unlock()
		stdout.emit(hookproto.Event{Ev: hookproto.EvProcess, Proc: &hookproto.Proc{PID: id, Exe: exe, State: hookproto.ProcDisconnected}})
		return 0
	}
	onCreate := func(code *uint16, name *byte, tp *threadParam, _ uintptr) uintptr {
		stdout.emit(hookproto.Event{Ev: hookproto.EvThread, Op: hookproto.OpCreate, Thread: toThread(code, name, tp)})
		return 0
	}
	onDestroy := func(code *uint16, name *byte, tp *threadParam) uintptr {
		stdout.emit(hookproto.Event{Ev: hookproto.EvThread, Op: hookproto.OpDestroy, Thread: toThread(code, name, tp)})
		return 0
	}
	onOutput := func(code *uint16, name *byte, tp *threadParam, text *uint16) uintptr {
		s := utf16PtrToString(text)
		if s != "" {
			stdout.emit(hookproto.Event{Ev: hookproto.EvText, Thread: toThread(code, name, tp), Text: s})
		}
		return 0
	}
	onInfo := func(kind uintptr, text *uint16) uintptr {
		level := hookproto.LogConsole
		switch uint32(kind) {
		case 1:
			level = hookproto.LogWarning
		case 2:
			level = hookproto.LogEmuWarning
		case 3:
			level = hookproto.LogEmuConnected
		case 4:
			level = hookproto.LogEngine
		}
		stdout.emit(hookproto.Event{Ev: hookproto.EvLog, Level: level, Text: utf16PtrToString(text)})
		return 0
	}
	onHookInsert := func(pid uintptr, addr uintptr, code *uint16) uintptr {
		stdout.emit(hookproto.Event{Ev: hookproto.EvLog, Level: hookproto.LogConsole,
			Text: fmt.Sprintf("hook inserted in pid %d at %#x: %s", uint32(pid), uint64(addr), utf16PtrToString(code))})
		return 0
	}
	noop2 := func(a, b uintptr) uintptr { return 0 }
	i18n := func(a uintptr) uintptr { return 0 } // nullptr: keep LunaHook's built-in (English/Chinese) strings
	noop3 := func(a, b, c uintptr) uintptr { return 0 }
	return [10]uintptr{
		syscall.NewCallback(onConnect),
		syscall.NewCallback(onDisconnect),
		syscall.NewCallback(onCreate),
		syscall.NewCallback(onDestroy),
		syscall.NewCallback(onOutput),
		syscall.NewCallback(onInfo),
		syscall.NewCallback(onHookInsert),
		syscall.NewCallback(noop2), // embed text callback (unused)
		syscall.NewCallback(i18n),
		syscall.NewCallback(noop3), // emulator game info (unused)
	}
}

func readCommands(out chan<- hookproto.Command) {
	defer close(out)
	sc := bufio.NewScanner(os.Stdin)
	sc.Buffer(make([]byte, 64*1024), 1<<20)
	for sc.Scan() {
		var c hookproto.Command
		if json.Unmarshal(sc.Bytes(), &c) == nil {
			out <- c
		}
	}
}

// ---- Wine integration ----

// makeSystemProcess asks Wine to treat vnhook like services.exe: it does not
// keep the prefix's wineserver alive, so Steam sees the game as closed as
// soon as the game exits, and the returned event fires at that moment. On
// real Windows (or an old Wine) this fails harmlessly and returns 0.
func makeSystemProcess() syscall.Handle {
	if procNtSetInfoProcess.Find() != nil {
		return 0
	}
	var ev syscall.Handle
	st, _, _ := procNtSetInfoProcess.Call(uintptr(^uintptr(0)), processWineMakeProcessSystem,
		uintptr(unsafe.Pointer(&ev)), unsafe.Sizeof(uintptr(0)))
	if st != 0 {
		return 0
	}
	return ev
}

// ---- process helpers ----

func listProcesses() ([]hookproto.Proc, error) {
	snap, err := syscall.CreateToolhelp32Snapshot(th32csSnapProcess, 0)
	if err != nil {
		return nil, err
	}
	defer syscall.CloseHandle(snap)
	var e syscall.ProcessEntry32
	e.Size = uint32(unsafe.Sizeof(e))
	if err := syscall.Process32First(snap, &e); err != nil {
		return nil, err
	}
	var out []hookproto.Proc
	for {
		out = append(out, hookproto.Proc{PID: e.ProcessID, Exe: syscall.UTF16ToString(e.ExeFile[:])})
		if err := syscall.Process32Next(snap, &e); err != nil {
			break
		}
	}
	return out, nil
}

func processName(pid uint32) string {
	procs, err := listProcesses()
	if err != nil {
		return ""
	}
	for _, p := range procs {
		if p.PID == pid {
			return p.Exe
		}
	}
	return ""
}

// processBits returns 32 for WOW64 processes, 64 otherwise (on a 64-bit OS).
func processBits(pid uint32) int {
	h, err := syscall.OpenProcess(processQueryLimited, false, pid)
	if err != nil {
		return 64
	}
	defer syscall.CloseHandle(h)
	var wow int32
	if r, _, _ := procIsWow64Process.Call(uintptr(h), uintptr(unsafe.Pointer(&wow))); r != 0 && wow != 0 {
		return 32
	}
	if runtime.GOARCH == "386" {
		return 32
	}
	return 64
}

// injectLocal loads dll into pid with CreateRemoteThread(LoadLibraryW). The
// target must have the same bitness as this process.
func injectLocal(pid uint32, dll string) error {
	if _, err := os.Stat(dll); err != nil {
		return err
	}
	path, err := syscall.UTF16FromString(dll)
	if err != nil {
		return err
	}
	h, err := syscall.OpenProcess(processInjectAccess, false, pid)
	if err != nil {
		return fmt.Errorf("OpenProcess: %w", err)
	}
	defer syscall.CloseHandle(h)
	size := uintptr(len(path) * 2)
	mem, _, err := procVirtualAllocEx.Call(uintptr(h), 0, size, memCommitReserve, pageReadWrite)
	if mem == 0 {
		return fmt.Errorf("VirtualAllocEx: %w", err)
	}
	defer procVirtualFreeEx.Call(uintptr(h), mem, 0, memRelease)
	if r, _, err := procWriteProcessMemory.Call(uintptr(h), mem, uintptr(unsafe.Pointer(&path[0])), size, 0); r == 0 {
		return fmt.Errorf("WriteProcessMemory: %w", err)
	}
	runtime.KeepAlive(path)
	if err := procLoadLibraryW.Find(); err != nil {
		return err
	}
	th, _, err := procCreateRemoteThread.Call(uintptr(h), 0, 0, procLoadLibraryW.Addr(), mem, 0, 0)
	if th == 0 {
		return fmt.Errorf("CreateRemoteThread: %w", err)
	}
	defer syscall.CloseHandle(syscall.Handle(th))
	if ev, _ := syscall.WaitForSingleObject(syscall.Handle(th), 30000); ev != syscall.WAIT_OBJECT_0 {
		return errors.New("LoadLibraryW in the target did not finish within 30 s")
	}
	var code uint32
	procGetExitCodeThread.Call(th, uintptr(unsafe.Pointer(&code)))
	if code == 0 {
		return errors.New("LoadLibraryW failed in the target (DLL missing or already loaded)")
	}
	return nil
}

// injectVia32BitHelper runs vnhook32.exe -inject, because a 64-bit process
// cannot call the 32-bit LoadLibraryW of a WOW64 target directly.
func injectVia32BitHelper(dir string, pid uint32, dll string) error {
	helper := filepath.Join(dir, "vnhook32.exe")
	if _, err := os.Stat(helper); err != nil {
		return fmt.Errorf("32-bit game needs %s: %w", helper, err)
	}
	cmd := exec.Command(helper, "-inject", strconv.FormatUint(uint64(pid), 10), dll)
	b, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("%v: %s", err, strings.TrimSpace(string(b)))
	}
	return nil
}

// ---- string helpers ----

func utf16PtrToString(p *uint16) string {
	if p == nil {
		return ""
	}
	n := 0
	for ptr := unsafe.Pointer(p); *(*uint16)(ptr) != 0; ptr = unsafe.Add(ptr, 2) {
		n++
		if n > 1<<20 {
			break
		}
	}
	return syscall.UTF16ToString(unsafe.Slice(p, n))
}

func bytePtrToString(p *byte) string {
	if p == nil {
		return ""
	}
	n := 0
	for ptr := unsafe.Pointer(p); *(*byte)(ptr) != 0; ptr = unsafe.Add(ptr, 1) {
		n++
		if n > 4096 {
			break
		}
	}
	return string(unsafe.Slice(p, n))
}
