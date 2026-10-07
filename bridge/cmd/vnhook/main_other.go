//go:build !windows

// Command vnhook is a Windows program (it runs inside a game's Wine prefix);
// see main_windows.go. Build it with GOOS=windows.
package main

import (
	"fmt"
	"os"
)

func main() {
	fmt.Fprintln(os.Stderr, "vnhook is a Windows program; build it with GOOS=windows and run it under Wine/Proton")
	os.Exit(2)
}
