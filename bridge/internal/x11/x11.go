// Package x11 holds the few X11 operations hoshi-bridge needs, implemented
// natively (pure Go) so that no xprop/xclip binaries are required on SteamOS.
package x11

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"time"

	"github.com/jezek/xgb"
	"github.com/jezek/xgb/xproto"

	"github.com/steindekker/hoshi-reader/bridge/internal/desktop"
)

func init() {
	// xgb logs "Could not get authority info" for every unauthenticated
	// connection, which is the normal case for gamescope's Xwayland.
	xgb.Logger = log.New(io.Discard, "", 0)
}

// Conn is an X connection plus its default root window.
type Conn struct {
	X       *xgb.Conn
	Root    xproto.Window
	Display string
	cancel  context.CancelFunc
}

// Dial connects to a local display (":0") through its unix socket, using a
// cookie from desktop.XAuthCookie when one exists. The connection is closed
// when ctx ends, which unblocks any pending request.
func Dial(ctx context.Context, display string) (*Conn, error) {
	path, ok := desktop.X11SocketPath(display)
	if !ok {
		return nil, fmt.Errorf("invalid display %q", display)
	}
	var d net.Dialer
	dctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	nc, err := d.DialContext(dctx, "unix", path)
	if err != nil {
		return nil, err
	}
	// Bound the connection setup handshake.
	_ = nc.SetDeadline(time.Now().Add(3 * time.Second))
	var xc *xgb.Conn
	if cookie := desktop.XAuthCookie(display); cookie != "" {
		xc, err = xgb.NewConnNetWithCookieHex(nc, cookie)
	} else {
		xc, err = xgb.NewConnNet(nc)
	}
	if err != nil {
		nc.Close()
		return nil, fmt.Errorf("x11 %s: %w", display, err)
	}
	_ = nc.SetDeadline(time.Time{})
	setup := xproto.Setup(xc)
	if setup == nil || len(setup.Roots) == 0 {
		xc.Close()
		return nil, fmt.Errorf("x11 %s: no screens", display)
	}
	lctx, lcancel := context.WithCancel(ctx)
	c := &Conn{X: xc, Root: setup.DefaultScreen(xc).Root, Display: display, cancel: lcancel}
	go func() {
		<-lctx.Done()
		// Closing the socket (not xgb's graceful Close, which needs a
		// round trip) unblocks pending replies even if the server hangs.
		nc.Close()
	}()
	return c, nil
}

// Close closes the connection.
func (c *Conn) Close() { c.cancel() }

// Atom interns an atom. With onlyIfExists, 0 means it does not exist.
func (c *Conn) Atom(name string, onlyIfExists bool) (xproto.Atom, error) {
	r, err := xproto.InternAtom(c.X, onlyIfExists, uint16(len(name)), name).Reply()
	if err != nil {
		return 0, err
	}
	if r == nil {
		return 0, errors.New("x11 connection closed")
	}
	return r.Atom, nil
}

// RootCardinal reads a 32-bit CARDINAL property from the root window.
func (c *Conn) RootCardinal(prop xproto.Atom) (uint32, bool, error) {
	r, err := xproto.GetProperty(c.X, false, c.Root, prop, xproto.AtomCardinal, 0, 1).Reply()
	if err != nil {
		return 0, false, err
	}
	if r == nil {
		return 0, false, errors.New("x11 connection closed")
	}
	if r.Format != 32 || len(r.Value) < 4 {
		return 0, false, nil
	}
	return xgb.Get32(r.Value), true, nil
}

// SetRootCardinal sets a 32-bit CARDINAL property on the root window.
func (c *Conn) SetRootCardinal(prop xproto.Atom, v uint32) error {
	buf := make([]byte, 4)
	xgb.Put32(buf, v)
	return xproto.ChangePropertyChecked(c.X, xproto.PropModeReplace, c.Root, prop, xproto.AtomCardinal, 32, 1, buf).Check()
}

// GamescopeServerID returns the GAMESCOPE_XWAYLAND_SERVER_ID that gamescope
// sets on the root window of each Xwayland it spawns; ok is false for any
// other X server (e.g. KWin's Xwayland in Desktop Mode).
func (c *Conn) GamescopeServerID() (id uint32, ok bool, err error) {
	atom, err := c.Atom("GAMESCOPE_XWAYLAND_SERVER_ID", true)
	if err != nil || atom == 0 {
		return 0, false, err
	}
	return c.RootCardinal(atom)
}

// FindGamescopeDisplay returns the gamescope Xwayland display, preferring
// server id 0 (the primary one) when gamescope runs several.
func FindGamescopeDisplay(ctx context.Context, candidates []string) (string, error) {
	best, bestID := "", uint32(1<<31)
	var lastErr error
	for _, d := range candidates {
		cctx, cancel := context.WithTimeout(ctx, 3*time.Second)
		c, err := Dial(cctx, d)
		if err != nil {
			cancel()
			lastErr = err
			continue
		}
		id, ok, err := c.GamescopeServerID()
		c.Close()
		cancel()
		if err != nil {
			lastErr = err
			continue
		}
		if ok && id < bestID {
			best, bestID = d, id
		}
	}
	if best == "" {
		if lastErr == nil {
			lastErr = errors.New("no gamescope Xwayland display found")
		}
		return "", lastErr
	}
	return best, nil
}
