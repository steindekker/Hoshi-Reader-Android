package x11

import (
	"context"
	"time"
)

// GamescopeScreenshotAtom is the root-window property gamescope watches for
// debug screenshot requests (steamcompmgr.cpp). Gamescope writes the image
// to /tmp/gamescope.png (older builds: /tmp/gamescope_<date>.png) and
// deletes the property once the file has been written. The non-debug
// GAMESCOPECTRL_REQUEST_SCREENSHOT is what the Steam client itself uses for
// Steam+R1, so we avoid it to not confuse Steam.
const GamescopeScreenshotAtom = "GAMESCOPECTRL_DEBUG_REQUEST_SCREENSHOT"

// RequestGamescopeScreenshot sets the request property on display's root
// window with the given screenshot type (gamescope_control screenshot_type:
// 1 base plane, 2 all real layers, 3 full composition, 4 screen buffer) and
// waits until gamescope deletes it again, i.e. the screenshot is written.
func RequestGamescopeScreenshot(ctx context.Context, display string, shotType uint32) error {
	c, err := Dial(ctx, display)
	if err != nil {
		return err
	}
	defer c.Close()
	atom, err := c.Atom(GamescopeScreenshotAtom, false)
	if err != nil {
		return err
	}
	if err := c.SetRootCardinal(atom, shotType); err != nil {
		return err
	}
	tick := time.NewTicker(25 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-tick.C:
		}
		_, present, err := c.RootCardinal(atom)
		if err != nil {
			return err
		}
		if !present {
			return nil
		}
	}
}
