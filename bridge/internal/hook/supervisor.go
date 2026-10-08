package hook

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/steindekker/hoshi-reader/bridge/internal/hookproto"
)

// Hook states reported in /api/status.
const (
	StateIdle      = "idle"
	StateInjecting = "injecting"
	StateAttached  = "attached"
	StateError     = "error"
)

// SourceID is the line-log source id of automatically hooked text.
const SourceID = "lunahook"

// ErrDisabled is returned by Select when hooking is turned off.
var ErrDisabled = errors.New("text hooking is disabled")

// PublicStatus is the "hook" object of /api/status.
type PublicStatus struct {
	Enabled          bool    `json:"enabled"`
	State            string  `json:"state"`
	Game             *string `json:"game"`
	PID              *int    `json:"pid"`
	SelectedThreadID *string `json:"selectedThreadId"`
	Mode             string  `json:"mode"`
	Error            *string `json:"error,omitempty"`
	Engine           *string `json:"engine,omitempty"`
}

// SupervisorOptions configures automatic injection.
type SupervisorOptions struct {
	Enabled  bool
	DataDir  string // ~/.local/share/hoshi-bridge
	Filter   Filter
	CodePage int
	FlushMs  int
	Interval time.Duration // process scan period
	Settle   time.Duration // do not inject into a process younger than this
	Scanner  Scanner
	FS       FS
	HostEnv  map[string]string
	Dist     Dist
	Selector *Selector
	// OnState is called when the hook source connects or disconnects.
	// A nil err clears the previous error.
	OnState func(connected bool, err error)
	// OnThreads is called when the thread list or the selection changes.
	OnThreads func()
	Log       *slog.Logger
	Now       func() time.Time
}

// Supervisor watches for Proton games and runs vnhook.exe inside the
// game's own Wine prefix.
type Supervisor struct {
	opts SupervisorOptions
	sel  *Selector

	mu       sync.Mutex
	state    string
	game     string
	pid      int
	errMsg   string
	engine   string
	retryAt  time.Time
	session  *session
	lastPush time.Time
}

type session struct {
	pid    int
	exe    string
	cancel context.CancelFunc
	done   chan struct{}
}

// NewSupervisor starts in the idle state. It does not scan until Run.
func NewSupervisor(opts SupervisorOptions) *Supervisor {
	if opts.Log == nil {
		opts.Log = slog.Default()
	}
	if opts.Now == nil {
		opts.Now = time.Now
	}
	if opts.Interval <= 0 {
		opts.Interval = 2 * time.Second
	}
	if opts.Settle <= 0 {
		opts.Settle = 3 * time.Second
	}
	if opts.CodePage == 0 {
		opts.CodePage = 932
	}
	if opts.FlushMs == 0 {
		opts.FlushMs = 500
	}
	if opts.Scanner.ProcDir == "" {
		opts.Scanner.ProcDir = "/proc"
		// UID 0 is the zero value, and it would only see root processes.
		// Tests that scan a fake procfs set ProcDir and UID themselves.
		if opts.Scanner.UID == 0 {
			opts.Scanner.UID = os.Getuid()
		}
	}
	if opts.FS == nil {
		opts.FS = OSFS{}
	}
	if opts.Selector == nil {
		opts.Selector = NewSelector(SelectorOptions{Log: opts.Log})
	}
	if opts.OnState == nil {
		opts.OnState = func(bool, error) {}
	}
	if opts.OnThreads == nil {
		opts.OnThreads = func() {}
	}
	if len(opts.Dist.Files) == 0 {
		opts.Dist = LunaDist
	}
	s := &Supervisor{opts: opts, sel: opts.Selector, state: StateIdle}
	// The selector calls back into the supervisor. The callback must not
	// run while the selector holds its lock; Selector already releases it
	// before OnChange.
	prev := opts.Selector.onChange
	opts.Selector.onChange = func(important bool) {
		if prev != nil {
			prev(important)
		}
		s.threadsChanged(important)
	}
	return s
}

// Run scans until ctx is cancelled, then detaches from the game.
func (s *Supervisor) Run(ctx context.Context) {
	if !s.opts.Enabled {
		return
	}
	s.scan(ctx)
	tick := time.NewTicker(s.opts.Interval)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			s.stopSession()
			s.setStatus(StateIdle, "", 0, "", "")
			s.opts.OnState(false, nil)
			return
		case <-tick.C:
			s.scan(ctx)
		}
	}
}

// View is the status object.
func (s *Supervisor) View() PublicStatus {
	id, mode := s.sel.Selected()
	s.mu.Lock()
	defer s.mu.Unlock()
	st := PublicStatus{
		Enabled: s.opts.Enabled,
		State:   s.state,
		Mode:    mode,
	}
	if st.Mode == "" {
		st.Mode = ModeAuto
	}
	if s.game != "" {
		g := s.game
		st.Game = &g
	}
	if s.pid != 0 {
		p := s.pid
		st.PID = &p
	}
	if id != "" {
		st.SelectedThreadID = &id
	}
	if s.errMsg != "" {
		e := s.errMsg
		st.Error = &e
	}
	if s.engine != "" {
		e := s.engine
		st.Engine = &e
	}
	return st
}

// Threads is the current thread list.
func (s *Supervisor) Threads() Snapshot { return s.sel.Snapshot() }

// Select forwards a manual choice, or a return to automatic selection
// when threadID is nil.
func (s *Supervisor) Select(threadID *string) error {
	if !s.opts.Enabled {
		return ErrDisabled
	}
	return s.sel.Select(threadID)
}

func (s *Supervisor) scan(ctx context.Context) {
	if ctx.Err() != nil {
		return
	}
	games, servers := s.opts.Scanner.Scan(s.opts.Filter)
	s.mu.Lock()
	sess := s.session
	retryAt := s.retryAt
	s.mu.Unlock()
	if sess != nil {
		if !containsGame(games, sess.pid, sess.exe) {
			s.opts.Log.Info("hooked game exited", "exe", sess.exe, "pid", sess.pid)
			s.stopSession()
		}
		return
	}
	now := s.opts.Now()
	if now.Before(retryAt) {
		return
	}
	g, ok := pickGame(games, systemUptime(), s.opts.Settle)
	if !ok {
		if s.currentState() != StateIdle {
			s.setStatus(StateIdle, "", 0, "", "")
			s.opts.OnState(false, nil)
		}
		return
	}
	s.startSession(ctx, g, servers)
}

func (s *Supervisor) startSession(ctx context.Context, g GameProcess, servers []Wineserver) {
	childCtx, cancel := context.WithCancel(ctx)
	sess := &session{pid: g.PID, exe: g.Exe, cancel: cancel, done: make(chan struct{})}
	s.mu.Lock()
	if s.session != nil {
		s.mu.Unlock()
		cancel()
		return
	}
	s.session = sess
	s.mu.Unlock()
	s.setStatus(StateInjecting, g.Exe, g.PID, "", "")
	s.opts.OnState(false, nil)
	s.opts.Log.Info("game detected", "exe", g.Exe, "pid", g.PID, "prefix", g.Prefix)
	go func() {
		defer close(sess.done)
		defer func() {
			s.mu.Lock()
			if s.session == sess {
				s.session = nil
			}
			s.mu.Unlock()
			s.sel.Reset()
		}()
		s.runChild(childCtx, g, servers)
	}()
}

func (s *Supervisor) stopSession() {
	s.mu.Lock()
	sess := s.session
	s.mu.Unlock()
	if sess == nil {
		return
	}
	sess.cancel()
	select {
	case <-sess.done:
	case <-time.After(6 * time.Second):
		s.opts.Log.Warn("hook helper did not exit after detach")
	}
}

func (s *Supervisor) runChild(ctx context.Context, g GameProcess, servers []Wineserver) {
	dir := filepath.Join(s.opts.DataDir, "lunahook")
	if err := s.opts.Dist.Ensure(dir); err != nil {
		s.fail(ctx, g, fmt.Errorf("LunaHook files: %w", err))
		return
	}
	vnhook := filepath.Join(dir, "vnhook.exe")
	if _, err := os.Stat(vnhook); err != nil {
		s.fail(ctx, g, fmt.Errorf("vnhook.exe is not installed in %s (re-run install.sh)", dir))
		return
	}
	host := s.opts.HostEnv
	if host == nil {
		host = HostEnv()
	}
	launch, err := PrepareLaunch(g, servers, host, s.opts.FS)
	if err != nil {
		s.fail(ctx, g, err)
		return
	}
	cmd := exec.Command(launch.Wine, vnhook,
		"-exe", g.Exe,
		"-codepage", strconv.Itoa(s.opts.CodePage),
		"-flush", strconv.Itoa(s.opts.FlushMs),
	)
	cmd.Env = launch.Env
	cmd.Dir = dir
	stdin, err := cmd.StdinPipe()
	if err != nil {
		s.fail(ctx, g, err)
		return
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		s.fail(ctx, g, err)
		return
	}
	cmd.Stderr = &lineWriter{fn: func(line string) {
		s.opts.Log.Warn("vnhook", "err", line)
	}}
	s.opts.Log.Info("launching hook helper", "wine", launch.Wine, "exe", g.Exe, "prefix", launch.Prefix)
	if err := cmd.Start(); err != nil {
		s.fail(ctx, g, fmt.Errorf("start vnhook: %w", err))
		return
	}
	waitCh := make(chan error, 1)
	go func() { waitCh <- cmd.Wait() }()

	lines := make(chan string)
	go func() {
		sc := bufio.NewScanner(stdout)
		sc.Buffer(make([]byte, 64*1024), 1<<20)
		for sc.Scan() {
			lines <- sc.Text()
		}
		close(lines)
	}()

	for {
		select {
		case <-ctx.Done():
			quitHelper(cmd, stdin, waitCh)
			s.setStatus(StateIdle, "", 0, "", "")
			s.opts.OnState(false, nil)
			return
		case line, ok := <-lines:
			if !ok {
				err := <-waitCh
				s.finish(ctx, g, err)
				return
			}
			s.handleLine(line, g.Exe)
		}
	}
}

func (s *Supervisor) finish(ctx context.Context, g GameProcess, err error) {
	if ctx.Err() != nil {
		s.setStatus(StateIdle, "", 0, "", "")
		s.opts.OnState(false, nil)
		return
	}
	if !processAlive(s.opts.Scanner.ProcDir, g.PID) {
		s.opts.Log.Info("game exited", "exe", g.Exe, "pid", g.PID)
		s.setStatus(StateIdle, "", 0, "", "")
		s.opts.OnState(false, nil)
		return
	}
	msg := "vnhook exited"
	if err != nil && err.Error() != "" {
		msg = "vnhook exited: " + err.Error()
	}
	s.backoff(15 * time.Second)
	s.setStatus(StateError, g.Exe, g.PID, "", msg)
	s.opts.OnState(false, errors.New(msg))
	s.opts.Log.Warn("hook helper exited while the game is still running", "exe", g.Exe, "err", msg)
}

func (s *Supervisor) fail(ctx context.Context, g GameProcess, err error) {
	if ctx.Err() != nil {
		return
	}
	s.backoff(15 * time.Second)
	s.setStatus(StateError, g.Exe, g.PID, "", err.Error())
	s.opts.OnState(false, err)
	s.opts.Log.Warn("could not hook game", "exe", g.Exe, "pid", g.PID, "err", err)
}

func (s *Supervisor) backoff(d time.Duration) {
	s.mu.Lock()
	s.retryAt = s.opts.Now().Add(d)
	s.mu.Unlock()
}

func (s *Supervisor) handleLine(line, exe string) {
	line = strings.TrimSpace(line)
	if line == "" || line[0] != '{' {
		if line != "" {
			s.opts.Log.Debug("vnhook", "out", line)
		}
		return
	}
	var ev hookproto.Event
	if err := json.Unmarshal([]byte(line), &ev); err != nil {
		s.opts.Log.Debug("vnhook", "out", line)
		return
	}
	s.handleEvent(ev, exe)
}

func (s *Supervisor) handleEvent(ev hookproto.Event, exe string) {
	switch ev.Ev {
	case hookproto.EvProcess:
		if ev.Proc == nil {
			return
		}
		switch ev.Proc.State {
		case hookproto.ProcConnected:
			s.setStatus(StateAttached, exe, s.currentPID(), s.currentEngine(), "")
			s.opts.OnState(true, nil)
			s.opts.Log.Info("hook attached", "exe", exe, "winPid", ev.Proc.PID)
		case hookproto.ProcDisconnected:
			s.setStatus(StateInjecting, exe, s.currentPID(), s.currentEngine(), "hook disconnected")
			s.opts.OnState(false, errors.New("hook disconnected"))
		case hookproto.ProcExited:
			s.opts.Log.Info("hooked process exited", "exe", ev.Proc.Exe, "winPid", ev.Proc.PID)
		}
	case hookproto.EvLog:
		if ev.Level == hookproto.LogEngine && ev.Text != "" {
			s.setStatus(s.currentState(), exe, s.currentPID(), ev.Text, "")
			s.opts.Log.Info("engine detected", "exe", exe, "engine", ev.Text)
		} else if ev.Text != "" && ev.Level != hookproto.LogConsole {
			s.opts.Log.Info("lunahook", "level", ev.Level, "text", ev.Text)
		} else if ev.Text != "" {
			s.opts.Log.Debug("lunahook", "text", ev.Text)
		}
	case hookproto.EvThread:
		if ev.Thread == nil {
			return
		}
		if ev.Op == hookproto.OpDestroy {
			s.sel.RemoveThread(*ev.Thread)
		} else {
			s.sel.AddThread(*ev.Thread, exe)
		}
	case hookproto.EvText:
		if ev.Thread == nil {
			return
		}
		s.sel.Output(*ev.Thread, exe, ev.Text)
	case hookproto.EvError:
		s.opts.Log.Warn("vnhook", "err", ev.Text)
		if s.currentState() != StateAttached && ev.Text != "" {
			s.setStatus(StateError, exe, s.currentPID(), s.currentEngine(), ev.Text)
			s.opts.OnState(false, errors.New(ev.Text))
		}
	case hookproto.EvHello:
		s.opts.Log.Info("hook helper started", "version", ev.Version, "arch", ev.Arch, "winPid", ev.PID)
	case hookproto.EvExit:
		s.opts.Log.Info("hook helper stopping", "reason", ev.Text)
	}
}

func (s *Supervisor) threadsChanged(important bool) {
	s.mu.Lock()
	if !important && s.opts.Now().Sub(s.lastPush) < 2*time.Second {
		s.mu.Unlock()
		return
	}
	s.lastPush = s.opts.Now()
	s.mu.Unlock()
	s.opts.OnThreads()
}

func (s *Supervisor) setStatus(state, game string, pid int, engine, errMsg string) {
	s.mu.Lock()
	s.state = state
	s.game = game
	s.pid = pid
	s.engine = engine
	s.errMsg = errMsg
	s.mu.Unlock()
}

func (s *Supervisor) currentState() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.state
}

func (s *Supervisor) currentPID() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.pid
}

func (s *Supervisor) currentEngine() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.engine
}

func containsGame(games []GameProcess, pid int, exe string) bool {
	for _, g := range games {
		if g.PID == pid && strings.EqualFold(g.Exe, exe) {
			return true
		}
	}
	return false
}

// pickGame returns the newest game once it has been running for at least
// settle. An older process is not chosen while a newer one is still
// starting, so a launcher cannot steal the hook from the game that just
// appeared. A zero uptime or a zero start tick (unreadable proc data)
// does not block injection.
func pickGame(games []GameProcess, uptime, settle time.Duration) (GameProcess, bool) {
	if len(games) == 0 {
		return GameProcess{}, false
	}
	g := games[len(games)-1]
	age, known := processAge(g.StartTick, uptime)
	if !known || age >= settle {
		return g, true
	}
	return GameProcess{}, false
}

func processAge(startTick uint64, uptime time.Duration) (time.Duration, bool) {
	if startTick == 0 || uptime <= 0 {
		return 0, false
	}
	const hz = 100 // USER_HZ on Linux amd64
	start := time.Duration(startTick) * time.Second / hz
	if start > uptime {
		return 0, false
	}
	return uptime - start, true
}

func systemUptime() time.Duration {
	b, err := os.ReadFile("/proc/uptime")
	if err != nil {
		return 0
	}
	field, _, _ := strings.Cut(string(b), " ")
	sec, err := strconv.ParseFloat(field, 64)
	if err != nil {
		return 0
	}
	return time.Duration(sec * float64(time.Second))
}

func processAlive(procDir string, pid int) bool {
	if procDir == "" {
		procDir = "/proc"
	}
	_, err := os.Stat(filepath.Join(procDir, strconv.Itoa(pid)))
	return err == nil
}

func quitHelper(cmd *exec.Cmd, stdin io.WriteCloser, waitCh <-chan error) {
	if stdin != nil {
		_, _ = io.WriteString(stdin, "{\"cmd\":\"quit\"}\n")
		_ = stdin.Close()
	}
	select {
	case <-waitCh:
	case <-time.After(3 * time.Second):
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
		}
		<-waitCh
	}
}

// lineWriter logs each newline-terminated chunk.
type lineWriter struct {
	fn  func(string)
	buf []byte
}

func (w *lineWriter) Write(p []byte) (int, error) {
	w.buf = append(w.buf, p...)
	for {
		i := indexByte(w.buf, '\n')
		if i < 0 {
			break
		}
		line := strings.TrimSpace(string(w.buf[:i]))
		w.buf = w.buf[i+1:]
		if line != "" && w.fn != nil {
			w.fn(line)
		}
	}
	if len(w.buf) > 8192 {
		w.fn(string(w.buf))
		w.buf = nil
	}
	return len(p), nil
}

func indexByte(b []byte, c byte) int {
	for i, x := range b {
		if x == c {
			return i
		}
	}
	return -1
}
