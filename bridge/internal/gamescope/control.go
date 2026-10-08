// Package gamescope is a minimal Wayland client for gamescope's private
// gamescope_control protocol (protocol/gamescope-control.xml in
// ValveSoftware/gamescope), used to request screenshots in Game Mode.
//
// Only the handful of wire messages we need are implemented:
//
//	wl_display.sync / get_registry, wl_registry.bind,
//	gamescope_control.take_screenshot (since v3) and its screenshot_taken event.
package gamescope

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"time"

	"github.com/steindekker/hoshi-reader/bridge/internal/desktop"
)

const (
	interfaceName = "gamescope_control"
	minVersion    = 3 // take_screenshot appeared in v3 (gamescope 3.13.x, Dec 2023)

	// Object ids we allocate (client ids start at 2; 1 is wl_display).
	idDisplay  = 1
	idRegistry = 2
	idCallback = 3
	idControl  = 4

	// Opcodes.
	displaySync        = 0
	displayGetRegistry = 1
	displayEvError     = 0
	registryBind       = 0
	registryEvGlobal   = 0
	callbackEvDone     = 0
	controlTakeShot    = 2
	controlEvShotTaken = 2
)

// Screenshot types from the protocol's screenshot_type enum.
const (
	TypeBasePlaneOnly   = 1 // just the game, no overlays
	TypeAllRealLayers   = 2 // game + overlays
	TypeFullComposition = 3
	TypeScreenBuffer    = 4
)

// SocketPaths returns candidate gamescope Wayland sockets: the one named by
// $GAMESCOPE_WAYLAND_DISPLAY first, then every gamescope-N in the runtime dir.
func SocketPaths() []string {
	dir := desktop.RuntimeDir()
	var out []string
	seen := map[string]bool{}
	add := func(p string) {
		if !seen[p] {
			seen[p] = true
			out = append(out, p)
		}
	}
	if name := os.Getenv("GAMESCOPE_WAYLAND_DISPLAY"); name != "" {
		if filepath.IsAbs(name) {
			add(name)
		} else {
			add(filepath.Join(dir, name))
		}
	}
	for _, n := range desktop.WaylandSockets("gamescope-") {
		// Skip gamescope-0-ei (libei) and other helper sockets.
		if socketName.MatchString(n) {
			add(filepath.Join(dir, n))
		}
	}
	return out
}

var socketName = regexp.MustCompile(`^gamescope-[0-9]+$`)

// Client is a connection that has bound gamescope_control.
type Client struct {
	conn    net.Conn
	version uint32
	Socket  string
}

// Connect opens socket and binds gamescope_control (>= v3).
func Connect(ctx context.Context, socket string) (*Client, error) {
	var d net.Dialer
	dctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	nc, err := d.DialContext(dctx, "unix", socket)
	if err != nil {
		return nil, err
	}
	c := &Client{conn: nc, Socket: socket}
	if dl, ok := ctx.Deadline(); ok {
		_ = nc.SetDeadline(dl)
	} else {
		_ = nc.SetDeadline(time.Now().Add(5 * time.Second))
	}
	if err := c.bind(); err != nil {
		nc.Close()
		return nil, err
	}
	return c, nil
}

// Close closes the connection.
func (c *Client) Close() error { return c.conn.Close() }

func (c *Client) bind() error {
	if err := c.send(idDisplay, displayGetRegistry, u32(idRegistry)); err != nil {
		return err
	}
	if err := c.send(idDisplay, displaySync, u32(idCallback)); err != nil {
		return err
	}
	var name, version uint32
	found := false
	for {
		obj, op, body, err := c.read()
		if err != nil {
			return err
		}
		switch {
		case obj == idDisplay && op == displayEvError:
			return protocolError(body)
		case obj == idRegistry && op == registryEvGlobal:
			r := reader{b: body}
			gName := r.uint()
			iface := r.string()
			gVer := r.uint()
			if r.err == nil && iface == interfaceName {
				name, version, found = gName, gVer, true
			}
		case obj == idCallback && op == callbackEvDone:
			if !found {
				return errors.New("gamescope_control not advertised (not a gamescope socket?)")
			}
			if version < minVersion {
				return fmt.Errorf("gamescope_control v%d lacks take_screenshot (need v%d)", version, minVersion)
			}
			c.version = minVersion
			// wl_registry.bind with an untyped new_id: name, interface, version, id.
			args := append(u32(name), str(interfaceName)...)
			args = append(args, u32(minVersion)...)
			args = append(args, u32(idControl)...)
			return c.send(idRegistry, registryBind, args)
		}
	}
}

// TakeScreenshot asks gamescope to write a screenshot to path (the extension
// selects the format; use .png) and waits for the screenshot_taken event.
// Gamescope sends no event when a capture fails, so ctx must carry a deadline.
func (c *Client) TakeScreenshot(ctx context.Context, path string, shotType uint32) error {
	if dl, ok := ctx.Deadline(); ok {
		_ = c.conn.SetDeadline(dl)
	}
	stop := context.AfterFunc(ctx, func() { _ = c.conn.SetDeadline(time.Now()) })
	defer stop()

	args := append(str(path), u32(shotType)...)
	args = append(args, u32(0)...)
	if err := c.send(idControl, controlTakeShot, args); err != nil {
		return err
	}
	for {
		obj, op, body, err := c.read()
		if err != nil {
			if ctx.Err() != nil {
				return fmt.Errorf("gamescope did not report the screenshot: %w", ctx.Err())
			}
			return err
		}
		switch {
		case obj == idDisplay && op == displayEvError:
			return protocolError(body)
		case obj == idControl && op == controlEvShotTaken:
			r := reader{b: body}
			// The event is broadcast to every gamescope_control client, so
			// only accept the one for our path.
			if p := r.string(); r.err == nil && p == path {
				return nil
			}
		}
	}
}

func protocolError(body []byte) error {
	r := reader{b: body}
	obj := r.uint()
	code := r.uint()
	msg := r.string()
	return fmt.Errorf("wayland protocol error on object %d (code %d): %s", obj, code, msg)
}

// send writes one request: header (object id, size<<16|opcode) + args.
func (c *Client) send(obj, opcode uint32, args []byte) error {
	size := 8 + len(args)
	buf := make([]byte, 8, size)
	binary.LittleEndian.PutUint32(buf[0:], obj)
	binary.LittleEndian.PutUint32(buf[4:], uint32(size)<<16|opcode)
	buf = append(buf, args...)
	_, err := c.conn.Write(buf)
	return err
}

func (c *Client) read() (obj, opcode uint32, body []byte, err error) {
	var hdr [8]byte
	if _, err = io.ReadFull(c.conn, hdr[:]); err != nil {
		return
	}
	obj = binary.LittleEndian.Uint32(hdr[0:])
	word := binary.LittleEndian.Uint32(hdr[4:])
	opcode = word & 0xffff
	size := int(word >> 16)
	if size < 8 {
		err = fmt.Errorf("bad wayland message size %d", size)
		return
	}
	body = make([]byte, size-8)
	_, err = io.ReadFull(c.conn, body)
	return
}

func u32(v uint32) []byte {
	b := make([]byte, 4)
	binary.LittleEndian.PutUint32(b, v)
	return b
}

// str encodes a Wayland string: length incl. NUL, bytes, NUL, pad to 4.
func str(s string) []byte {
	n := len(s) + 1
	padded := (n + 3) &^ 3
	b := make([]byte, 4+padded)
	binary.LittleEndian.PutUint32(b, uint32(n))
	copy(b[4:], s)
	return b
}

type reader struct {
	b   []byte
	err error
}

func (r *reader) uint() uint32 {
	if r.err != nil || len(r.b) < 4 {
		r.err = io.ErrUnexpectedEOF
		return 0
	}
	v := binary.LittleEndian.Uint32(r.b)
	r.b = r.b[4:]
	return v
}

func (r *reader) string() string {
	n := int(r.uint())
	if r.err != nil {
		return ""
	}
	padded := (n + 3) &^ 3
	if n == 0 || len(r.b) < padded {
		r.err = io.ErrUnexpectedEOF
		return ""
	}
	s := string(r.b[:n-1])
	r.b = r.b[padded:]
	return s
}
